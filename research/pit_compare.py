"""Compare the fixed assessment with a subsequent PIT run using stable mutant identities."""

import csv
import json
from collections import Counter
from pathlib import Path
import xml.etree.ElementTree as ET


research = Path(__file__).resolve().parent
before_directory = research / "pit_results_assessment"
after_directory = research / "pit_results"


def load_mutants(directory):
    mutants = {}
    for mutation in ET.parse(directory / "mutations.xml").getroot().findall("mutation"):
        identity = (
            mutation.findtext("mutatedClass"), mutation.findtext("mutatedMethod"),
            mutation.findtext("methodDescription"), mutation.findtext("mutator"),
            ",".join(item.text or "" for item in mutation.findall("indexes/index")),
        )
        if identity in mutants:
            raise ValueError(f"Duplicate mutant identity in {directory}")
        mutants[identity] = mutation
    return mutants


before = load_mutants(before_directory)
after = load_mutants(after_directory)
if before.keys() != after.keys():
    raise RuntimeError("The generated mutant sets differ; inspect the configuration before comparing scores.")

before_summary = json.loads((before_directory / "summary.json").read_text(encoding="utf-8"))
after_summary = json.loads((after_directory / "summary.json").read_text(encoding="utf-8"))
if before_summary["sources_sha256_lf"] != after_summary["sources_sha256_lf"]:
    raise RuntimeError("Production source hashes differ between mutation assessments.")

before_test_names = {test.get("name") for test in ET.parse(before_directory / "linecoverage.xml").iter("test")}
after_test_names = {test.get("name") for test in ET.parse(after_directory / "linecoverage.xml").iter("test")}
if before_test_names != after_test_names:
    raise RuntimeError("The test identities recorded in the coverage reports differ.")

differences = []
for identity in sorted(before):
    original = before[identity]
    repeated = after[identity]
    differences.append({
        "mutatedClass": identity[0], "mutatedMethod": identity[1],
        "methodDescription": identity[2], "mutator": identity[3], "indexes": identity[4],
        "sourceFile": original.findtext("sourceFile"), "lineNumber": original.findtext("lineNumber"),
        "description": original.findtext("description"),
        "assessmentA": original.get("status"), "assessmentB": repeated.get("status"),
        "killingTestB": repeated.findtext("killingTest", default=""),
    })
with (after_directory / "assessment_comparison.csv").open("w", encoding="utf-8", newline="") as output:
    writer = csv.DictWriter(output, fieldnames=list(differences[0]))
    writer.writeheader()
    writer.writerows(differences)

test_file = "src/test/kotlin/io/github/Michal_Antosiewicz/apiary_management_system/WorkflowIntegrationTest.kt"
comparison = {
    "same_mutant_identities": True,
    "same_production_source_hashes_canonical_lf": True,
    "same_coverage_test_identities": True,
    "coverage_test_identity_count": len(before_test_names),
    "total_mutants": len(before),
    "assessment_A_statuses": before_summary["statuses"],
    "assessment_B_statuses": after_summary["statuses"],
    "status_transitions": dict(Counter(row["assessmentA"] + " -> " + row["assessmentB"] for row in differences)),
    "test_fixture_sha256_A": before_summary["configuration_sha256"][test_file],
    "test_fixture_sha256_B": after_summary["configuration_sha256"][test_file],
    "test_fixture_sha256_lf_A": before_summary["configuration_sha256_lf"][test_file],
    "test_fixture_sha256_lf_B": after_summary["configuration_sha256_lf"][test_file],
    "mutations_xml_sha256_A": before_summary["mutations_xml_sha256"],
    "mutations_xml_sha256_B": after_summary["mutations_xml_sha256"],
}
(after_directory / "assessment_comparison.json").write_text(
    json.dumps(comparison, indent=2) + "\n", encoding="utf-8")
print(json.dumps(comparison, indent=2))
