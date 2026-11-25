#!/usr/bin/env python3
"""Describe which existing test groups detected each prespecified fault.

This post-run attribution reads recorded outcomes; it does not run or alter tests.
The 90 matrix identities come from the successful baseline CSV. The remaining
25 integration identities are the dedicated validation and sequence cases,
including 12 wintering regressions.
"""
from __future__ import annotations

import csv
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "research/mutation_results"


def main() -> None:
    manifest = json.loads((OUT / "manifest.json").read_text(encoding="utf-8"))
    with (ROOT / "verification/workflow-matrix.csv").open(encoding="utf-8") as stream:
        matrix = {"/".join((r["initial_state"], r["treatment_condition"], r["command"])) for r in csv.DictReader(stream)}
    assert len(matrix) == 90
    rows = []
    for entry in manifest:
        result = json.loads((OUT / (entry["id"] + ".json")).read_text(encoding="utf-8"))
        patch = (OUT / (entry["id"] + ".patch")).read_bytes().replace(b"\r\n", b"\n")
        assert hashlib.sha256(patch).hexdigest() == entry["patch_sha256"], entry["id"]
        if result["status"] not in {"detected", "survived"}:
            rows.append(dict(id=entry["id"], title=entry["title"], status=result["status"],
                             matrix_failures="not_evaluable", dedicated_failures="not_evaluable",
                             matrix_detected=False, full_suite_detected=False))
            continue
        tests = result["tests"]
        assert len(tests) == 115
        assert {t["name"] for t in tests if t["name"] in matrix} == matrix
        assert sum(t["name"] not in matrix for t in tests) == 25
        matrix_failed = sum(t["outcome"] == "failed" and t["name"] in matrix for t in tests)
        dedicated_failed = sum(t["outcome"] == "failed" and t["name"] not in matrix for t in tests)
        assert matrix_failed + dedicated_failed == result["failures"]
        rows.append(dict(id=entry["id"], title=entry["title"], status=result["status"],
                         matrix_failures=matrix_failed, dedicated_failures=dedicated_failed,
                         matrix_detected=matrix_failed > 0, full_suite_detected=matrix_failed + dedicated_failed > 0))
    with (OUT / "test_group_attribution.csv").open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    summary = dict(planned_faults=len(manifest), evaluable_faults=sum(r["status"] in {"detected", "survived"} for r in rows),
                   matrix_cases=90, dedicated_cases=25,
                   faults_detected_by_matrix=sum(r["matrix_detected"] for r in rows),
                   faults_detected_by_full_integration_suite=sum(r["full_suite_detected"] for r in rows),
                   faults_detected_only_by_dedicated_cases=[r["id"] for r in rows if r["full_suite_detected"] and not r["matrix_detected"]],
                   interpretation="Descriptive post-run attribution within the prespecified ten-fault set; test assertions were held fixed within each assessment. Assessment B follows the documented assertion strengthening after assessment A and PIT feedback.")
    (OUT / "test_group_attribution.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    lines = ["# Prespecified workflow fault-detection experiment", "",
             "The unmodified baseline passed 167 cases, including 115 workflow HTTP/PostgreSQL cases, "
             "43 decision-comparison and structural-transfer cases, and 9 service cases. "
             "Each of the ten specified faults was then applied separately to an isolated source copy, using the same 115 workflow cases. "
             "The 25 dedicated cases include 12 wintering regressions.", "",
             "| ID | Fault | Failures in matrix (90) | Failures in dedicated cases (25) | Outcome |",
             "|---|---|---:|---:|---|"]
    lines += [f"| {r['id']} | {r['title']} | {r['matrix_failures']} | {r['dedicated_failures']} | {r['status']} |" for r in rows]
    lines += ["", f"The matrix detected {summary['faults_detected_by_matrix']}/{len(manifest)} specified faults. "
              f"The complete integration suite detected {summary['faults_detected_by_full_integration_suite']}/{len(manifest)}. "
              "This is descriptive attribution within the selected fault set. Test assertions were held fixed within each assessment. "
              "These are assessment B results, following assertion strengthening informed by the initial automated PIT assessment. "
              "Assessment A, its outcomes, the A-to-B patch, and source-hash linkage are preserved in "
              "`../mutation_results_assessment/`. Both assessments retain the same 115 case identities; B adds metadata and "
              "inspection-audit assertions, a matching optional treatment identifier, and redelivery within an existing event case. "
              "The ten mutation definitions and production code are unchanged between A and B. The summary field "
              "`unchanged_existing_test_assertions` describes the frozen assertions during the recorded run, not equality between A and B.", "",
              "`manifest.json` and M01–M10.patch define all planned faults. M01–M10.json retain every executed test identity and outcome; "
              "`summary.json` also retains non-detection categories. `test_group_attribution.csv` is generated by "
              "`research/summarize_workflow_mutations.py`. The manually reviewed `independent_failure_review.csv` connects observed "
              "failures to specific assertions and documents interpretation limits.", "",
              "The causal review records the actual failing observation and its location for each variant. "
              "In M07 a business rejection becomes an exception. Tests may fail at the HTTP assertion or while decoding "
              "a decision identifier from the error response. M08 removes the lock annotation used by both the command and the controlling "
              "transaction in its test. Its observable missing lock wait supports a protocol violation, not an independently "
              "observed incorrect harvest decision under concurrency. These results are not a correctness proof or an exhaustive "
              "assessment of mutation operators or interleavings.", "",
              "Raw logs and source copies remain in the local work directory and are not part of this publication artifact. "
              "Patch files use LF line endings; their byte SHA-256 values match the manifest. See ../README.md for reproduction.", ""]
    (OUT / "README.md").write_text("\n".join(lines), encoding="utf-8")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
