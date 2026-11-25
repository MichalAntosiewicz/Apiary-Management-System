"""Export every PIT mutant with source context and aggregate status counts."""

import argparse
import csv
import hashlib
import json
from collections import Counter, defaultdict
from pathlib import Path
import xml.etree.ElementTree as ET


repository = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("report_directory", nargs="?", default="research/pit_results")
arguments = parser.parse_args()
report_directory = repository / arguments.report_directory
report = report_directory / "mutations.xml"
mutations = ET.parse(report).getroot().findall("mutation")
rows = []
source_cache = {}
for mutation in mutations:
    row = dict(mutation.attrib)
    for name in (
        "sourceFile", "mutatedClass", "mutatedMethod", "methodDescription",
        "lineNumber", "mutator", "killingTest", "description",
    ):
        row[name] = mutation.findtext(name, default="")
    row["indexes"] = ",".join(item.text or "" for item in mutation.findall("indexes/index"))
    row["blocks"] = ",".join(item.text or "" for item in mutation.findall("blocks/block"))
    source_name = row["sourceFile"]
    if source_name not in source_cache:
        source = next((repository / "src" / "main").rglob(source_name))
        source_cache[source_name] = source.read_text(encoding="utf-8").splitlines()
    line_number = int(row["lineNumber"])
    source_lines = source_cache[source_name]
    row["sourceLine"] = source_lines[line_number - 1].strip() if 0 < line_number <= len(source_lines) else ""
    rows.append(row)

columns = [
    "status", "detected", "numberOfTestsRun", "mutatedClass", "mutatedMethod",
    "sourceFile", "lineNumber", "mutator", "description", "indexes", "blocks",
    "killingTest", "methodDescription", "sourceLine",
]
with (report_directory / "mutants_with_source.csv").open("w", encoding="utf-8", newline="") as output:
    writer = csv.DictWriter(output, fieldnames=columns, extrasaction="ignore")
    writer.writeheader()
    writer.writerows(rows)

counts = Counter({status: 0 for status in (
    "KILLED", "SURVIVED", "NO_COVERAGE", "TIMED_OUT", "NON_VIABLE",
    "MEMORY_ERROR", "RUN_ERROR", "NOT_STARTED", "STARTED", "EQUIVALENT",
)})
counts.update(row["status"] for row in rows)
classes = defaultdict(Counter)
methods = defaultdict(Counter)
for row in rows:
    classes[row["mutatedClass"]][row["status"]] += 1
    methods[row["mutatedClass"] + "." + row["mutatedMethod"]][row["status"]] += 1

summary = {
    "total": len(rows),
    "statuses": dict(sorted(counts.items())),
    "killed_fraction_all": counts["KILLED"] / len(rows) if rows else None,
    "killed_fraction_killed_plus_survived": (
        counts["KILLED"] / (counts["KILLED"] + counts["SURVIVED"])
        if counts["KILLED"] + counts["SURVIVED"] else None
    ),
    "classes": {name: dict(value) for name, value in sorted(classes.items())},
    "methods": {name: dict(value) for name, value in sorted(methods.items())},
    "mutations_xml_sha256": hashlib.sha256(report.read_bytes()).hexdigest(),
    "sources_sha256": {
        str(path.relative_to(repository)): hashlib.sha256(path.read_bytes()).hexdigest()
        for path in sorted((repository / "src" / "main").rglob("*.kt"))
        if path.name in source_cache
    },
    "sources_sha256_lf": {
        path.relative_to(repository).as_posix(): hashlib.sha256(path.read_bytes().replace(b"\r\n", b"\n")).hexdigest()
        for path in sorted((repository / "src" / "main").rglob("*.kt"))
        if path.name in source_cache
    },
    "configuration_sha256": {
        name: hashlib.sha256((repository / name).read_bytes()).hexdigest()
        for name in (
            "build.gradle.kts", "settings.gradle.kts", "gradle/wrapper/gradle-wrapper.properties",
            "research/pit.gradle", "research/run_pit.ps1",
            "src/test/kotlin/io/github/Michal_Antosiewicz/apiary_management_system/WorkflowIntegrationTest.kt",
        )
    },
    "configuration_sha256_lf": {
        name: hashlib.sha256((repository / name).read_bytes().replace(b"\r\n", b"\n")).hexdigest()
        for name in (
            "build.gradle.kts", "settings.gradle.kts", "gradle/wrapper/gradle-wrapper.properties",
            "research/pit.gradle", "research/run_pit.ps1",
            "src/test/kotlin/io/github/Michal_Antosiewicz/apiary_management_system/WorkflowIntegrationTest.kt",
        )
    },
    "hash_convention": {
        "raw": "SHA256 of exact file bytes",
        "canonical_lf": "SHA256 of file bytes after replacing CRLF with LF, with POSIX relative-path keys",
    },
}
(report_directory / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
print(json.dumps(summary, indent=2))
