"""Manual review of the fixed 17 November 2026 PIT report, including all non-killed mutants."""

import csv
import hashlib
import json
from collections import Counter, defaultdict
from pathlib import Path


directory = Path(__file__).resolve().parent / "pit_results_assessment"
expected_hash = "2a521c297f540833b7602ca2322b501cc00e10f158cfdd9fc4161608193d2644"
if hashlib.sha256((directory / "mutations.xml").read_bytes()).hexdigest() != expected_hash:
    raise RuntimeError("This manual analysis belongs to the archived initial PIT report.")
with (directory / "mutants_with_source.csv").open(encoding="utf-8", newline="") as source:
    rows = list(csv.DictReader(source))

# Row numbers identify the unchanged report order. Each exported row also includes
# the full PIT class, method, mutator and bytecode instruction indexes.
assessments = {}


def assess(numbers, category, reason):
    for number in numbers:
        if number in assessments:
            raise ValueError(f"Duplicate assessment for mutant row {number}")
        assessments[number] = (category, reason)


assess([3, 4], "Kotlin collection implementation",
       "The Kotlin SMAP maps generated line 240 to active.any at source line 189. These mutations affect the Collection/empty-collection fast path. Iteration still produces the observed any result for the repository Lists. Both remain SURVIVED in the raw denominator.")
assess([12], "Missing audit assertion",
       "Removing the inspection-triggered operational transition audit preserves the inspected operational state. The suite checks that state but does not require the corresponding inspection event-to-transition row.")
assess([14], "Missing selection and metadata assertion",
       "The Kotlin SMAP maps generated line 234 to maxByOrNull at source line 115. The boundary mutation changes selection when withdrawal endpoints tie. The suite checks rejection but does not check which tied treatment supplies the explanation.")
assess([15], "Missing duplicate-event scenario",
       "HTTP commands allocate a fresh eventId. The suite does not redeliver the same event, so removing the existing-decision short circuit has no distinguishing assertion.")
assess([22], "Missing reason-code assertion",
       "The active-treatment rejection remains rejected, while its reasonCode can change. The suite asserts OPERATIONAL_STATE_BLOCKED for wintering but does not assert ACTIVE_WITHDRAWAL for every treatment rejection.")
assess([23, 24], "Missing explanation assertion",
       "The mutation changes the harvest response/decision explanation. The suite checks status and persisted approval but does not assert the explanation for the affected operational/treatment branch.")
assess([28], "Missing transition-reason assertion",
       "The transition row remains present with the same foreign key and state endpoints. The suite does not assert the APPROVED/REJECTED prefix in its textual reason.")
assess([30, 31], "Missing blocked-until assertion",
       "The mutation changes the nullable blockedUntil result while approval remains unchanged. The suite does not compare HTTP blockedUntil and the persisted restriction endpoint against the contract.")
assess([32], "Missing selection and metadata assertion",
       "The Kotlin SMAP maps generated line 234 to maxByOrNull at source line 115. Removing its comparison can change which active treatment supplies the latest endpoint. Nonempty active treatments still block harvest, and selected endpoint/medicine metadata are not asserted.")
assess([33, 34, 98, 99, 100, 101, 102, 107, 108, 112, 113, 123, 124, 125, 126, 127, 128],
       "Kotlin non-null check",
       "The mutated call is a Kotlin runtime non-null check. The exercised repository, time, UUID, event-name or serialization result is non-null. Removing this check preserves the observed path. This contextual explanation does not remove the mutant from the denominator.")
assess([37, 104, 110, 131], "Uncovered missing-hive path",
       "The mutation is in an exception supplier for an absent hive. The suite constructs each requested hive, and PIT reports no execution of this supplier.")
assess([38, 39, 40, 44, 45, 46, 52, 53, 54, 56, 58, 60, 61, 62, 66, 67, 68, 69, 70],
       "Uncovered biological prototype branch",
       "The operational suite posts healthy inspection fixtures with no queen cells and nominal biological state. It does not enter this biological threshold, recovery or state-change branch. PIT reports NO_COVERAGE for the mutated instruction.")
assess([41, 42, 43, 50, 51, 55, 57, 59, 63, 64, 65],
       "Undistinguished biological prototype branch",
       "The suite exercises this conditional with healthy nominal inspection fixtures. It does not vary the relevant biological indicator around the rule boundary or assert the affected biological transition. The mutation survives these operational checks.")
assess([47, 84], "Listener lookup on existing hive",
       "The command service has already obtained an existing hive before publishing this event. The suite does not publish this listener event for an absent hive, so the nullable lookup/return branch is not distinguished.")
assess([49, 86], "Repeated decommissioned-state guard",
       "The command service rejects a decommissioned hive before this listener is invoked. Removing this listener-level guard preserves the HTTP outcome under the tested command entry point.")
assess([72, 73, 74, 75, 76], "Uncovered queen-replacement workflow",
       "WorkflowIntegrationTest exercises harvest, treatment and inspection commands. It does not publish QueenReplacedEvent, so this listener instruction has NO_COVERAGE.")
assess([77, 78, 79, 80, 81, 82, 83], "Uncovered swarm-intervention workflow",
       "WorkflowIntegrationTest exercises harvest, treatment and inspection commands. It does not publish SwarmInterventionAppliedEvent, so this listener instruction has NO_COVERAGE.")
assess([93], "Missing matching-body-ID case",
       "The suite covers omitted body hiveId and a mismatched body hiveId. It does not cover an explicitly supplied body hiveId matching the URL, which distinguishes this equality branch.")
assess([96, 120], "Repeated decommissioned-state guard",
       "After this service-level check is removed, the synchronous listener rejects the decommissioned hive and the enclosing transaction rolls back. The asserted HTTP status and zero persisted rows therefore remain unchanged.")
assess([97, 106, 115], "Repeated request validation",
       "HTTP controller @Valid rejects invalid requests before the service method. Valid requests also cover the service validator. The suite's direct service invocation supplies valid data, so removing this additional validation is not distinguished.")
assess([111], "Uncovered missing-decision path",
       "The unmutated synchronous harvest listener records a decision. No fixture suppresses that listener while preserving command execution, so the exception supplier for a missing decision has NO_COVERAGE.")
assess([116, 117], "Uncovered service validation-error formatting",
       "Invalid HTTP requests are rejected by controller validation. The selected suite does not enter the service's nonempty constraint-violation formatting path, and PIT reports NO_COVERAGE.")
assess([118], "Missing invalid-inspection service input",
       "The suite supplies valid inspection DTOs. It does not call recordInspection directly with constraint violations, so deleting its local validation branch is not distinguished.")
assess([121], "Same concrete event-name fallback",
       "This method constructs InspectionRecordedEvent. Its Kotlin simpleName and the fallback literal are both InspectionRecordedEvent, so the alternative produces the same persisted event type for this concrete construction.")
assess([122, 130], "Uncovered inspection validation-error formatting",
       "The selected suite submits valid inspections and never enters the nonempty constraint-violation formatting path. PIT reports NO_COVERAGE for this instruction.")

remaining = {number for number, row in enumerate(rows, start=1) if row["status"] != "KILLED"}
if set(assessments) != remaining:
    raise RuntimeError(f"Incomplete manual review: missing {remaining - set(assessments)}, extra {set(assessments) - remaining}")

scope_counts = defaultdict(Counter)
analysis_rows = []
for number, row in enumerate(rows, start=1):
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
    if number in assessments:
        category, reason = assessments[number]
        analysis_rows.append({"reportRow": number, **row, "bodyScope": scope,
                              "analysisCategory": category, "reason": reason})

with (directory / "non_killed_analysis.csv").open("w", encoding="utf-8", newline="") as output:
    writer = csv.DictWriter(output, fieldnames=list(analysis_rows[0]))
    writer.writeheader()
    writer.writerows(analysis_rows)
(directory / "body_scope_counts.json").write_text(
    json.dumps({name: dict(counts) for name, counts in scope_counts.items()}, indent=2) + "\n", encoding="utf-8")
print(f"Reviewed {len(analysis_rows)} non-killed mutants; all retained in the raw score.")
print(json.dumps({name: dict(counts) for name, counts in scope_counts.items()}, indent=2))
