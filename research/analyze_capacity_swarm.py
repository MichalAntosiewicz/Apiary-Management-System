#!/usr/bin/env python3
"""Evaluate assigned hive capacities with one marginal queen cell."""
from __future__ import annotations

import hashlib
import json
import sys
from collections import Counter
from pathlib import Path

sys.dont_write_bytecode = True
from analyze_adapter_sensitivity import BASELINE, CAPACITIES, OUT, evaluate, load_cases, write_csv
from analyze_beewise import CLASSES, THRESHOLDS, predict, scores

QUEEN_AGE = 1
PATTERN = "SOLID_COMPACT"
CELL_COUNT = 1
PLACEMENT = "MARGINAL"
STAGE = "OPEN_WITH_LARVA"
PESTS = False


def sha256_lf(path: Path) -> str:
    return hashlib.sha256(path.read_bytes().replace(b"\r\n", b"\n")).hexdigest()


def evaluate_case(case, capacity):
    _, honey_factor, varroa_representation = BASELINE
    converted = evaluate(case, capacity, honey_factor, varroa_representation, pattern=PATTERN)
    x = dict(bees=case["frames_of_bees"], brood=case["frames_of_brood"],
             honey=converted["honey_kg"], space=converted["free_space_percent"],
             varroa=converted["varroa_integer_input"], pattern=PATTERN,
             placement=PLACEMENT, stage=STAGE, cell_count=CELL_COUNT, pests=PESTS)
    score = scores(x, age=QUEEN_AGE)
    return {
        **case,
        "capacity_frames": capacity,
        "free_space_percent": converted["free_space_percent"],
        "honey_kg": converted["honey_kg"],
        "varroa_integer_input": converted["varroa_integer_input"],
        "swarming_score": score[CLASSES[0]],
        "supersedure_score": score[CLASSES[1]],
        "stress_score": score[CLASSES[2]],
        "emitted_alerts": ";".join(kind for kind in CLASSES[:3] if score[kind] >= THRESHOLDS[kind]),
        "selected_class": predict(score),
    }


def run():
    cases, dataset_sha256 = load_cases()
    reference_capacity, honey_factor, varroa_representation = BASELINE
    reference = {case["case_id"]: evaluate_case(case, reference_capacity) for case in cases}
    rows, scenarios = [], []
    for capacity in CAPACITIES:
        group = []
        for case in cases:
            ref = reference[case["case_id"]]
            row = evaluate_case(case, capacity)
            row["reference_selected_class"] = ref["selected_class"]
            row["changed_selected_class"] = int(row["selected_class"] != ref["selected_class"])
            row["changed_alert_set"] = int(row["emitted_alerts"] != ref["emitted_alerts"])
            row["changed_swarming_score"] = int(row["swarming_score"] != ref["swarming_score"])
            group.append(row)
        swarming = [row for row in group if CLASSES[0] in row["emitted_alerts"].split(";")]
        changed = [row for row in group if row["changed_selected_class"]]
        scenarios.append({
            "capacity_frames": capacity,
            "swarming_alerts": len(swarming),
            "colonies_with_swarming_alert": len({row["hive_id"] for row in swarming}),
            "stress_alerts": sum(CLASSES[2] in row["emitted_alerts"].split(";") for row in group),
            "changed_selected_visits": len(changed),
            "colonies_with_changed_selection": len({row["hive_id"] for row in changed}),
            "changed_alert_sets": sum(row["changed_alert_set"] for row in group),
            "changed_swarming_scores": sum(row["changed_swarming_score"] for row in group),
            "class_transitions": dict(sorted(Counter(
                row["reference_selected_class"] + " -> " + row["selected_class"] for row in changed).items())),
        })
        rows.extend(group)
    cases_path = OUT / "capacity_swarm_cases.csv"
    write_csv(cases_path, rows)
    dependencies = ("analyze_adapter_sensitivity.py", "analyze_beewise.py")
    summary = {
        "dataset_sha256": dataset_sha256,
        "source_sha256_lf": {
            name: sha256_lf(Path(__file__).with_name(name))
            for name in dependencies
        },
        "case_selection_sha256_lf": sha256_lf(OUT / "case_results.csv"),
        "cases_file": cases_path.name,
        "cases_sha256_lf": sha256_lf(cases_path),
        "visits": len(cases),
        "colonies": len({case["hive_id"] for case in cases}),
        "evaluations": len(rows),
        "reference_capacity_frames": reference_capacity,
        "honey_factor_kg_per_frame": honey_factor,
        "varroa_representation": varroa_representation,
        "assigned_inputs": {"queen_age": QUEEN_AGE, "brood_pattern": PATTERN, "cell_count": CELL_COUNT,
                            "cell_placement": PLACEMENT, "cell_stage": STAGE, "pest_signs": PESTS},
        "scenarios": scenarios,
    }
    (OUT / "capacity_swarm_summary.json").write_bytes((json.dumps(summary, indent=2) + "\n").encode("utf-8"))
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    run()
