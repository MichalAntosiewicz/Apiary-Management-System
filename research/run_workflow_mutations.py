#!/usr/bin/env python3
"""Execute the prespecified, single-fault workflow mutation experiment.

Each mutant receives an isolated source copy. All 103 existing HTTP/PostgreSQL
integration cases run, without changing their assertions. Compile errors and
incomplete runs are reported separately from test-detected faults. No external
database or application is used. Raw local logs stay in the supplied work dir;
the published report contains test identities and bounded failure summaries.
"""
from __future__ import annotations

import argparse
import csv
import difflib
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "research/mutation_results"
BASE = "src/main/kotlin/io/github/Michal_Antosiewicz/apiary_management_system/"
FSM = BASE + "service/HiveStateMachineService.kt"
OPS = BASE + "service/HiveOperationService.kt"
REPO = BASE + "domain/Repositories.kt"
EXPECTED_CASES = 103

# Frozen before execution. Each replacement must match exactly once.
MUTANTS = [
    ("M01", REPO, "t.withdrawalEndDate > :now", "t.withdrawalEndDate >= :now",
     "Include the withdrawal endpoint", "An off-by-one interval error retains a restriction at its exact endpoint."),
    ("M02", REPO, "t.startDate <= :now", "t.startDate < :now",
     "Exclude the treatment start", "An off-by-one interval error allows harvest at the exact treatment start."),
    ("M03", FSM, "active.any { event.occurredAt.isBefore(it.treatmentEndDate) }",
     "active.any { !event.occurredAt.isAfter(it.treatmentEndDate) }",
     "Include the administration endpoint", "Administration incorrectly takes precedence at its exact end."),
    ("M04", FSM, "active.any { event.occurredAt.isBefore(it.treatmentEndDate) }",
     "(active.isNotEmpty() && active.all { event.occurredAt.isBefore(it.treatmentEndDate) })",
     "Require every active treatment to be in administration", "Overlapping administration and withdrawal incorrectly select WITHDRAWAL."),
    ("M05", FSM,
     "hive.operationalState in setOf(OperationalState.TREATMENT_IN_PROGRESS, OperationalState.WITHDRAWAL) -> OperationalState.PREPARATION",
     "hive.operationalState in setOf(OperationalState.TREATMENT_IN_PROGRESS, OperationalState.WITHDRAWAL) -> hive.operationalState",
     "Retain expired treatment state", "A missed inspection window leaves a stale treatment or withdrawal state."),
    ("M06", FSM,
     "val unavailable = hive.operationalState in setOf(OperationalState.DECOMMISSIONED, OperationalState.WINTERING)",
     "val unavailable = hive.operationalState == OperationalState.DECOMMISSIONED",
     "Remove the wintering harvest guard", "A seasonal state restriction is omitted while other guards remain."),
    ("M07", FSM,
     "event.occurredAt, if (!unavailable) conflict?.withdrawalEndDate else null))",
     "event.occurredAt, if (!unavailable) conflict?.withdrawalEndDate else null))\n        if (!approved) throw IllegalStateException(\"Mutation: rejected business decision\")",
     "Throw after recording business rejection", "A business rejection is represented as an unchecked exception and rolls back its audit."),
    ("M08", REPO, "    @Lock(LockModeType.PESSIMISTIC_WRITE)\n", "",
     "Remove explicit per-hive write lock", "Concurrent operations no longer acquire the declared common lock before assigning event time."),
    ("M09", OPS,
     "val event = HoneyHarvestAttemptedEvent(hiveId = hiveId, occurredAt = Instant.now(clock).truncatedTo(ChronoUnit.MICROS),",
     "val event = HoneyHarvestAttemptedEvent(hiveId = hiveId, occurredAt = Instant.now(clock),",
     "Keep nanoseconds in harvest event time", "JSON event time diverges from PostgreSQL timestamp precision."),
    ("M10", FSM, "                eventId = event.eventId\n", "                eventId = null\n",
     "Omit transition event association", "The transition exists but loses its relational link to the accepted command."),
]


def source_hash() -> str:
    digest = hashlib.sha256()
    paths = [p for p in (ROOT / "src").rglob("*") if p.is_file()]
    paths += [ROOT / n for n in ("build.gradle.kts", "settings.gradle.kts", "gradlew", "gradlew.bat",
                               "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties")]
    for path in sorted(paths):
        digest.update(path.relative_to(ROOT).as_posix().encode())
        content = path.read_bytes()
        digest.update(content if path.suffix == ".jar" else content.replace(b"\r\n", b"\n"))
    return digest.hexdigest()


def clean_message(value: str, work: Path) -> str:
    value = value.replace(str(work), "<workdir>").replace(str(ROOT), "<repository>")
    for key in ("USERPROFILE", "JAVA_HOME", "GRADLE_USER_HOME", "TEMP", "TMP"):
        if os.environ.get(key):
            value = value.replace(os.environ[key], f"<{key}>")
    return value.splitlines()[0][:500] if value else ""


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work-dir", required=True, type=Path,
                        help="New local directory outside the repository; source copies and raw logs remain here")
    parser.add_argument("--offline", action="store_true", help="Use the existing Gradle dependency cache")
    args = parser.parse_args()
    work = args.work_dir.resolve()
    if work.is_relative_to(ROOT) or ROOT.is_relative_to(work):
        raise SystemExit("Use a separate work directory outside the repository")
    if work.exists() and any(work.iterdir()):
        raise SystemExit("Work directory must be new or empty; prior experiment evidence is never overwritten")
    baseline = json.loads((ROOT / "verification/verification_summary.json").read_text(encoding="utf-8"))
    if baseline["total_tests"] != 111 or any(baseline[k] for k in ("failures", "errors", "skipped")):
        raise SystemExit("First run and archive the complete successful 111-case baseline")
    for line in (ROOT / "verification/source_sha256.txt").read_text(encoding="utf-8").splitlines():
        if line.startswith("#") or not line.strip():
            continue
        expected, filename = line.split("  ", 1)
        path = ROOT / filename
        content = path.read_bytes()
        if path.suffix != ".jar":
            content = content.replace(b"\r\n", b"\n")
        if hashlib.sha256(content).hexdigest() != expected:
            raise SystemExit(f"Baseline archive does not match current input: {filename}")
    work.mkdir(parents=True, exist_ok=True)
    OUT.mkdir(parents=True, exist_ok=True)

    # Validate the entire prespecified mutation set before any Gradle execution.
    manifest = []
    for mid, filename, before, after, title, rationale in MUTANTS:
        original = (ROOT / filename).read_text(encoding="utf-8")
        if original.count(before) != 1:
            raise SystemExit(f"{mid}: expected one replacement anchor; found {original.count(before)}")
        changed = original.replace(before, after)
        patch = "".join(difflib.unified_diff(original.splitlines(True), changed.splitlines(True),
                                           fromfile="a/" + filename, tofile="b/" + filename))
        (OUT / f"{mid}.patch").write_bytes(patch.encode("utf-8"))
        manifest.append(dict(id=mid, file=filename, title=title, rationale=rationale,
                             patch_sha256=hashlib.sha256(patch.encode()).hexdigest()))
    (OUT / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    baseline_hash = source_hash()

    outcomes = []
    for entry, (mid, filename, before, after, _, _) in zip(manifest, MUTANTS):
        variant = work / mid
        variant.mkdir()
        for name in ("src", "gradle"):
            shutil.copytree(ROOT / name, variant / name)
        for name in ("build.gradle.kts", "settings.gradle.kts", "gradlew", "gradlew.bat"):
            shutil.copy2(ROOT / name, variant / name)
        path = variant / filename
        path.write_text(path.read_text(encoding="utf-8").replace(before, after), encoding="utf-8")
        command = (["cmd", "/d", "/c", "gradlew.bat"] if platform.system() == "Windows" else ["sh", "./gradlew"])
        command += ["test", "--tests", "*WorkflowIntegrationTest", "--no-daemon", "--console=plain"]
        if args.offline:
            command.append("--offline")
        print(f"{mid}: {entry['title']}: running all {EXPECTED_CASES} integration cases", flush=True)
        started = time.monotonic()
        timed_out = False
        with (variant / "gradle.log").open("w", encoding="utf-8") as log:
            try:
                process = subprocess.run(command, cwd=variant, stdout=log, stderr=subprocess.STDOUT,
                                         timeout=300, encoding="utf-8", errors="replace")
                exit_code = process.returncode
            except subprocess.TimeoutExpired:
                timed_out = True
                exit_code = None
        cases = []
        for report in sorted((variant / "build/test-results/test").glob("TEST-*.xml")):
            suite = ET.parse(report).getroot()
            for case in suite.findall("testcase"):
                issue = case.find("failure")
                if issue is None:
                    issue = case.find("error")
                cases.append(dict(class_name=case.get("classname"), name=case.get("name"),
                                  outcome="failed" if issue is not None else "skipped" if case.find("skipped") is not None else "passed",
                                  failure_type=issue.get("type", "") if issue is not None else "",
                                  failure_message=clean_message(issue.get("message", ""), variant) if issue is not None else ""))
        failed = sum(c["outcome"] == "failed" for c in cases)
        skipped = sum(c["outcome"] == "skipped" for c in cases)
        log_text = (variant / "gradle.log").read_text(encoding="utf-8", errors="replace")
        if timed_out:
            status = "timeout"
        elif len(cases) == EXPECTED_CASES and not skipped and failed and exit_code != 0:
            status = "detected"
        elif len(cases) == EXPECTED_CASES and not skipped and not failed and exit_code == 0:
            status = "survived"
        elif "compileKotlin FAILED" in log_text or "compileTestKotlin FAILED" in log_text:
            status = "compile_error"
        else:
            status = "incomplete_or_infrastructure_error"
        result = dict(**entry, status=status, exit_code=exit_code, cases=len(cases), failures=failed,
                      skipped=skipped, elapsed_seconds=round(time.monotonic() - started, 3),
                      command=command, tests=cases)
        outcomes.append(result)
        (OUT / f"{mid}.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        print(f"{mid}: {status}; cases={len(cases)}, failures={failed}", flush=True)
        summary = dict(experiment="Prespecified single-fault workflow mutations", baseline_source_sha256=baseline_hash,
                       unchanged_existing_test_assertions=True, expected_integration_cases_per_mutant=EXPECTED_CASES,
                       planned_mutants=len(MUTANTS), completed_mutants=len(outcomes),
                       counts={s: sum(r["status"] == s for r in outcomes) for s in
                               ("detected", "survived", "compile_error", "timeout", "incomplete_or_infrastructure_error")},
                       outcomes=[{k: v for k, v in r.items() if k != "tests"} for r in outcomes])
        (OUT / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
        with (OUT / "summary.csv").open("w", encoding="utf-8", newline="") as stream:
            fields = ["id", "title", "status", "cases", "failures", "skipped", "elapsed_seconds"]
            writer = csv.DictWriter(stream, fieldnames=fields)
            writer.writeheader()
            writer.writerows({k: r[k] for k in fields} for r in outcomes)
    if source_hash() != baseline_hash:
        raise SystemExit("Repository inputs changed during the experiment; results require review")
    print(json.dumps(summary["counts"], indent=2), flush=True)


if __name__ == "__main__":
    main()
