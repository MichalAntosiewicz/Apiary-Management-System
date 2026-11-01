#!/usr/bin/env python3
"""Archive a complete successful verification run without host names or console logs.

Run after the full Gradle test task from any working directory:
    python research/archive_verification.py
"""
from __future__ import annotations

import csv
import hashlib
import json
import os
import platform
import re
import shutil
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "verification"
EXPECTED_TESTS = 111


def main() -> None:
    reports = sorted((ROOT / "build/test-results/test").glob("TEST-*.xml"))
    parsed = [(path, ET.parse(path).getroot()) for path in reports]
    total = sum(int(s.attrib["tests"]) for _, s in parsed)
    failures = sum(int(s.attrib["failures"]) for _, s in parsed)
    errors = sum(int(s.attrib["errors"]) for _, s in parsed)
    skipped = sum(int(s.attrib.get("skipped", 0)) for _, s in parsed)
    if total != EXPECTED_TESTS or failures or errors or skipped:
        raise SystemExit(f"Archive requires the complete successful {EXPECTED_TESTS}-case suite; "
                         f"observed tests={total}, failures={failures}, errors={errors}, skipped={skipped}")
    OUT.mkdir(exist_ok=True)
    (OUT / "junit").mkdir(exist_ok=True)
    junit_directory = (OUT / "junit").resolve()
    if not junit_directory.is_relative_to(ROOT.resolve()):
        raise SystemExit("The JUnit export directory must stay inside this repository")
    export_names = ["TEST-" + s.attrib["name"].rsplit(".", 1)[-1] + ".xml" for _, s in parsed]
    if len(set(export_names)) != len(export_names):
        raise SystemExit("Test class names must be unique for stable JUnit export names")
    # Gradle may shorten long XML names differently across hosts. Replace the controlled export set.
    for old_report in junit_directory.glob("TEST-*.xml"):
        if not old_report.resolve().is_relative_to(junit_directory):
            raise SystemExit("A previous report resolves outside the JUnit export directory")
        old_report.unlink()
    suites = []
    for (_, suite), export_name in zip(parsed, export_names):
        # Retain test identity, UTC run time, duration and outcome; omit machine metadata and logs.
        suite.attrib.pop("hostname", None)
        for child in list(suite):
            if child.tag in {"system-out", "system-err", "properties"}:
                suite.remove(child)
        suites.append({key: suite.attrib[key] for key in
                       ("name", "tests", "failures", "errors", "skipped", "time")})
        ET.indent(suite)
        ET.ElementTree(suite).write(junit_directory / export_name, encoding="utf-8", xml_declaration=True)
    for name in ("workflow-matrix.csv", "boundary-sequences.csv", "postgres-version.txt"):
        shutil.copy2(ROOT / "build/verification" / name, OUT / name)
    with (OUT / "workflow-matrix.csv").open(encoding="utf-8") as stream:
        matrix = list(csv.DictReader(stream))
    with (OUT / "boundary-sequences.csv").open(encoding="utf-8") as stream:
        boundaries = list(csv.DictReader(stream))
    build = (ROOT / "build.gradle.kts").read_text(encoding="utf-8")
    wrapper = (ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text(encoding="utf-8")
    java_release = {}
    release_path = Path(os.environ.get("JAVA_HOME", "")) / "release"
    if release_path.is_file():
        java_release = dict(re.findall(r'^(\w+)="([^"]*)"$', release_path.read_text(encoding="utf-8"), re.M))
    summary = {
        "artifact": "Apiary workflow verification research artifact",
        "full_suite_command": ".\\gradlew.bat test --no-daemon --offline" if platform.system() == "Windows" else "sh ./gradlew test --no-daemon --offline",
        "offline_note": "Omit --offline on the first run to obtain public dependencies.",
        "suites": suites,
        "total_tests": total,
        "failures": failures,
        "errors": errors,
        "skipped": skipped,
        "integration_cases": next(int(s["tests"]) for s in suites if s["name"].endswith("WorkflowIntegrationTest")),
        "matrix_cases": len(matrix),
        "matrix_http_status_counts": dict(Counter(row["http_status"] for row in matrix)),
        "matrix_state_mismatches": sum(row["actual_state"] != row["expected_state"] for row in matrix),
        "boundary_steps": len(boundaries),
        "field_derived_fixture_configurations": 6672,
        "author_defined_fixture_configurations": 20,
        "environment": {
            "operating_system": platform.system(),
            "architecture": platform.machine(),
            "java_version": java_release.get("JAVA_VERSION", "not recorded"),
            "java_implementor": java_release.get("IMPLEMENTOR", "not recorded"),
            "gradle_version": re.search(r"gradle-([\d.]+)-bin", wrapper).group(1),
            "kotlin_version": re.search(r'kotlin\("jvm"\) version "([^"]+)"', build).group(1),
            "spring_boot_version": re.search(r'id\("org.springframework.boot"\) version "([^"]+)"', build).group(1),
            "postgres_version": (OUT / "postgres-version.txt").read_text(encoding="utf-8").strip(),
        },
        "junit_export": "Successful test identities and outcomes retained; host names, properties and console streams omitted.",
    }
    (OUT / "verification_summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    sources = list((ROOT / "src").rglob("*")) + [ROOT / name for name in
        ("build.gradle.kts", "settings.gradle.kts", "gradlew", "gradlew.bat",
         "gradle/wrapper/gradle-wrapper.properties", "gradle/wrapper/gradle-wrapper.jar")]
    hashes = ["# SHA-256; UTF-8 text uses canonical LF line endings, binary files use their original bytes."]
    for path in sorted(p for p in sources if p.is_file()):
        content = path.read_bytes()
        if path.suffix != ".jar":
            content = content.replace(b"\r\n", b"\n")
        hashes.append(hashlib.sha256(content).hexdigest() + "  " + path.relative_to(ROOT).as_posix())
    (OUT / "source_sha256.txt").write_text("\n".join(hashes) + "\n", encoding="utf-8")
    print(json.dumps({key: summary[key] for key in
                      ("total_tests", "failures", "errors", "integration_cases", "matrix_cases", "boundary_steps")}, indent=2))


if __name__ == "__main__":
    main()
