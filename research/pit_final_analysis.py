"""Carry reviewed explanations into assessment B and flag every new non-killed mutant."""

import csv
import json
from collections import Counter, defaultdict
from pathlib import Path


research = Path(__file__).resolve().parent
assessment = research / "pit_results_assessment"
final = research / "pit_results"
identity_fields = ("mutatedClass", "mutatedMethod", "methodDescription", "mutator", "indexes")


def identity(row):
    return tuple(row[field] for field in identity_fields)


with (assessment / "non_killed_analysis.csv").open(encoding="utf-8", newline="") as source:
    earlier = {identity(row): row for row in csv.DictReader(source)}
with (final / "mutants_with_source.csv").open(encoding="utf-8", newline="") as source:
    rows = list(csv.DictReader(source))

reviewed = []
scope_counts = defaultdict(Counter)
for row in rows:
    method = row["mutatedMethod"]
    if row["sourceFile"] != "HiveStateMachineService.kt":
        scope = "Transactional command-service bodies"
    elif method in ("onQueenReplaced", "onSwarmInterventionApplied"):
        scope = "Biological intervention listener bodies"
    elif method == "onInspectionRecorded" and 36 <= int(row["lineNumber"]) <= 75:
        scope = "Inspection biological-rule body"
    elif method == "onInspectionRecorded":
        scope = "Inspection listener lookup, guard and operational delegation"
    else:
        scope = "Operational harvest, treatment and reconciliation listener bodies"
    scope_counts[scope][row["status"]] += 1
    if row["status"] == "KILLED":
        continue
    original = earlier.get(identity(row))
    if original is None or original["status"] != row["status"]:
        raise RuntimeError(f"New or changed non-killed status requires individual review: {identity(row)}")
    reason = original["reason"]
    category = original["analysisCategory"]
    if original["reportRow"] == "14":
        category = "Unexercised equal-endpoint treatment selection"
        reason = (
            "The Kotlin SMAP maps this maxByOrNull boundary mutation to source line 115. "
            "Assessment B checks the latest blockedUntil and treatment explanation for distinct endpoints. "
            "The mutation changes selection only when endpoints tie. The suite does not exercise "
            "equal withdrawal endpoints with different medicine names, so this boundary change survives."
        )
    reviewed.append({"assessmentAReportRow": original["reportRow"], **row,
                     "bodyScope": scope, "analysisCategory": category, "reason": reason})

with (final / "non_killed_analysis.csv").open("w", encoding="utf-8", newline="") as output:
    writer = csv.DictWriter(output, fieldnames=list(reviewed[0]))
    writer.writeheader()
    writer.writerows(reviewed)
(final / "body_scope_counts.json").write_text(
    json.dumps({scope: dict(counts) for scope, counts in scope_counts.items()}, indent=2) + "\n", encoding="utf-8")
print(f"Reviewed all {len(reviewed)} non-killed mutants in assessment B.")
print(json.dumps({scope: dict(counts) for scope, counts in scope_counts.items()}, indent=2))
