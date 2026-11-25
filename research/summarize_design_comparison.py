#!/usr/bin/env python3
"""Archive the completed decision-policy comparison and structural transfer example."""
from __future__ import annotations

import csv
import hashlib
import json
from pathlib import Path
import shutil
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "research/design_comparison"


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8-sig", newline="") as stream:
        return list(csv.DictReader(stream))


def checked_rows(output_name: str, fixture_name: str, count: int) -> list[dict[str, str]]:
    cases = read_csv(ROOT / "src/test/resources" / fixture_name)
    results = read_csv(ROOT / "build/verification" / output_name)
    assert len(cases) == len(results) == count
    assert len({r["case_id"] for r in results}) == count
    assert {r["case_id"] for r in cases} == {r["case_id"] for r in results}
    assert all(r["audit_verified"] == "true" for r in results)
    return results


def main() -> None:
    suites = [ET.parse(p).getroot() for p in (ROOT / "build/test-results/test").glob("TEST-*.xml")]
    suites = [s for s in suites if s.get("name", "").endswith(".WorkflowDesignComparisonTest")]
    assert len(suites) == 1, "Run WorkflowDesignComparisonTest before archiving"
    suite = suites[0]
    assert int(suite.get("tests", "0")) == 43
    assert all(int(suite.get(k, "0")) == 0 for k in ("failures", "errors", "skipped"))
    comparisons = checked_rows("workflow-design-comparison.csv", "workflow_design_cases.csv", 33)
    transfers = checked_rows("batch-transfer.csv", "batch_transfer_cases.csv", 10)
    assert all(r["expected_interval_approval"] == r["interval_approval"] for r in comparisons)
    assert all(r["expected_cached_approval"] == r["cached_approval"] for r in comparisons)
    assert all(r["expected_release"] == r["actual_release"] for r in transfers)
    differing = [r["case_id"] for r in comparisons if r["interval_approval"] != r["cached_approval"]]
    groups = {}
    for refresh in ("true", "false"):
        subset = [r for r in comparisons if r["refresh"] == refresh]
        groups["with_refresh" if refresh == "true" else "without_refresh"] = dict(
            cases=len(subset), agreeing_decisions=sum(r["interval_approval"] == r["cached_approval"] for r in subset),
            interval_approvals=sum(r["interval_approval"] == "true" for r in subset),
            cached_approvals=sum(r["cached_approval"] == "true" for r in subset))
    source_paths = [
        "src/test/kotlin/io/github/Michal_Antosiewicz/apiary_management_system/WorkflowDesignComparisonTest.kt",
        "src/test/resources/workflow_design_cases.csv", "src/test/resources/batch_transfer_cases.csv",
        "src/main/kotlin/io/github/Michal_Antosiewicz/apiary_management_system/service/HiveStateMachineService.kt",
        "src/main/kotlin/io/github/Michal_Antosiewicz/apiary_management_system/service/HiveOperationService.kt",
        "src/main/kotlin/io/github/Michal_Antosiewicz/apiary_management_system/domain/Repositories.kt",
    ]
    summary = dict(
        comparison_cases=len(comparisons), interval_contract_matches=len(comparisons), cached_contract_matches=len(comparisons),
        differing_decisions=len(differing), differing_case_ids=differing, comparison_groups=groups,
        transfer_cases=len(transfers), transfer_contract_matches=len(transfers),
        production_http_decisions_with_verified_audit=len(comparisons) + len(transfers),
        junit=dict(tests=43, failures=0, errors=0, skipped=0),
        evidence_scope="Production HTTP interval decisions compared with a reference cached-phase predicate on the same pre-request state. "
                       "The batch example tests a structural adapter into the same production guard.",
        source_sha256_canonical_lf={name: hashlib.sha256((ROOT / name).read_bytes().replace(b"\r\n", b"\n")).hexdigest()
                                    for name in source_paths})
    OUT.mkdir(parents=True, exist_ok=True)
    for name in ("workflow-design-comparison.csv", "batch-transfer.csv"):
        shutil.copyfile(ROOT / "build/verification" / name, OUT / name)
    (OUT / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: summary[k] for k in ("comparison_cases", "interval_contract_matches", "cached_contract_matches",
                                           "differing_decisions", "comparison_groups", "transfer_cases", "transfer_contract_matches")}, indent=2))


if __name__ == "__main__":
    main()
