#!/usr/bin/env python3
"""Run deterministic adapter-factor scenarios with the existing fixed rule engine.

Python 3.10+, standard library only. Run from any directory.
"""
from __future__ import annotations

import csv
import hashlib
import io
import json
import statistics
import sys
import zipfile
from collections import Counter
from itertools import product
from pathlib import Path

sys.dont_write_bytecode = True
from analyze_beewise import CLASSES, PATTERNS, THRESHOLDS, fixture, kotlin_round, predict, scores

HERE = Path(__file__).resolve().parent
OUT = HERE / "outputs"
CAPACITIES = (10, 20, 30)
HONEY_FACTORS = (1, 2, 3)
VARROA_SCENARIOS = ("raw_count_half", "raw_count", "raw_count_double", "rate_per_100_substituted")
BASELINE = (20, 2, "raw_count")
SOURCE_NAME = "Beewise_Colony_Assessment_2025_Data.csv"
README_URL = "https://zenodo.org/records/21907271/files/Beewise_Colony_Assessment_2025_README.txt?download=1"


def read_csv(path):
    with path.open(encoding="utf-8-sig", newline="") as stream:
        return list(csv.DictReader(stream))


def write_csv(path, rows):
    with path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]), lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def load_cases():
    with zipfile.ZipFile(HERE / "upstream_41521e6.zip") as archive:
        members = [n for n in archive.namelist() if n.endswith("/" + SOURCE_NAME) or n == SOURCE_NAME]
        if len(members) != 1:
            raise ValueError("Expected exactly one source dataset in the archived snapshot.")
        data = archive.read(members[0])
    rows = list(csv.DictReader(io.StringIO(data.decode("utf-8-sig"))))
    complete = {r["case_id"] for r in read_csv(OUT / "case_results.csv")
                if r["variant"] == "brood_agnostic_complete"}
    cases = []
    for row in rows:
        for visit in (1, 2, 3):
            case_id = row["hive"] + f"-V{visit}"
            if case_id not in complete:
                continue
            cases.append({
                "case_id": case_id, "hive_id": row["hive"], "visit": visit,
                "frames_of_bees": int(float(row[f"Lieb_Bees_{visit}"])),
                "frames_of_brood": int(float(row[f"Lieb_WorkerBrood_{visit}"]) + float(row[f"Lieb_DroneBrood_{visit}"])),
                "honey_frames": float(row[f"Lieb_Honey_{visit}"]),
                "raw_varroa_count": int(row[f"Varroa_{visit}"]),
                "mites_per_100_bees": float(row[f"Mite_per_100_{visit}"]),
                "associated_bee_count": float(row["BeesAW_3" if visit == 3 else f"Bees_{visit}"]),
            })
    cases.sort(key=lambda c: c["case_id"])
    if len(cases) != len(complete) or {c["case_id"] for c in cases} != complete:
        raise ValueError("Source visits do not match the existing quantitative-complete population.")
    return cases, hashlib.sha256(data).hexdigest()


def evaluate(case, capacity, honey_factor, varroa_scenario, pattern="SOLID_COMPACT"):
    varroa_inputs = {
        "raw_count_half": case["raw_varroa_count"] * 0.5,
        "raw_count": case["raw_varroa_count"],
        "raw_count_double": case["raw_varroa_count"] * 2,
        "rate_per_100_substituted": case["mites_per_100_bees"],
    }
    occupied = max(case["frames_of_bees"], case["frames_of_brood"]) + case["honey_frames"]
    x = dict(bees=case["frames_of_bees"], brood=case["frames_of_brood"],
             honey=kotlin_round(case["honey_frames"] * honey_factor, 2),
             space=kotlin_round((capacity - min(occupied, capacity)) / capacity * 100.0, 2),
             varroa=int(varroa_inputs[varroa_scenario]), pattern=pattern,
             placement="NONE", stage="NONE", cell_count=0, pests=False)
    s = scores(x, age=1)
    return {
        "free_space_percent": x["space"], "honey_kg": x["honey"], "varroa_integer_input": x["varroa"],
        "occupied_frame_equivalent": occupied, "occupied_exceeds_assigned_capacity": int(occupied > capacity),
        "swarming_score": s[CLASSES[0]], "supersedure_score": s[CLASSES[1]], "stress_score": s[CLASSES[2]],
        "emitted_alerts": ";".join(c for c in CLASSES[:3] if s[c] >= THRESHOLDS[c]),
        "selected_class": predict(s),
    }


def summarize(rows, capacity, honey_factor, varroa_scenario):
    changes = [r for r in rows if r["changed_selected_class"]]
    result = {
        "scenario_id": rows[0]["scenario_id"], "capacity_frames": capacity, "kg_per_honey_frame": honey_factor,
        "varroa_representation": varroa_scenario, "visits": len(rows), "colonies": len({r["hive_id"] for r in rows}),
        "selected_swarm": sum(r["selected_class"] == CLASSES[0] for r in rows),
        "selected_supersedure": sum(r["selected_class"] == CLASSES[1] for r in rows),
        "selected_stress": sum(r["selected_class"] == CLASSES[2] for r in rows),
        "selected_no_alert": sum(r["selected_class"] == CLASSES[3] for r in rows),
        "changed_selected_visits": len(changes), "colonies_with_changed_selection": len({r["hive_id"] for r in changes}),
        "changed_alert_sets": sum(r["changed_alert_set"] for r in rows),
        "occupied_exceeds_assigned_capacity": sum(r["occupied_exceeds_assigned_capacity"] for r in rows),
    }
    for name in ("swarming_score", "supersedure_score", "stress_score"):
        result[name + "_changed_visits"] = sum(r["delta_" + name] != 0 for r in rows)
        result[name + "_min"] = min(r[name] for r in rows)
        result[name + "_max"] = max(r[name] for r in rows)
        result[name + "_mean"] = statistics.mean(r[name] for r in rows)
        result[name + "_mean_absolute_change"] = statistics.mean(abs(r["delta_" + name]) for r in rows)
    return result


def jvm_fixture(row, reference):
    x = dict(bees=row["frames_of_bees"], brood=row["frames_of_brood"],
             honey=row["honey_kg"], space=row["free_space_percent"], varroa=row["varroa_integer_input"],
             pattern="SOLID_COMPACT", placement="NONE", stage="NONE", cell_count=0, pests=False)
    s = dict(zip(CLASSES[:3], (row["swarming_score"], row["supersedure_score"], row["stress_score"])))
    return fixture(row["case_id"], "adapter_sensitivity__" + row["scenario_id"],
                   reference["inspection_date"], x, 1, reference["proxy_or_scenario_label"], s)


def main():
    cases, source_sha = load_cases()
    baseline = {c["case_id"]: evaluate(c, *BASELINE) for c in cases}
    reference_fixtures = {r["case_id"]: r for r in read_csv(OUT / "jvm_cases.csv")
                          if r["variant"] == "age_brood_grid__age_1__SOLID_COMPACT"}
    if set(reference_fixtures) != set(baseline):
        raise ValueError("Reference fixture and sensitivity experiment contain different visits.")
    for key, result in baseline.items():
        f = reference_fixtures[key]
        expected = (f["expected_prediction"], float(f["swarming_score"]), float(f["supersedure_score"]), float(f["absconding_score"]))
        observed = (result["selected_class"], result["swarming_score"], result["supersedure_score"], result["stress_score"])
        if observed != expected:
            raise ValueError(f"Sensitivity baseline differs from the existing JVM fixture for {key}.")
    case_rows, summaries, brood_checks = [], [], 0
    for capacity, honey_factor, varroa_scenario in product(CAPACITIES, HONEY_FACTORS, VARROA_SCENARIOS):
        group = []
        for case in cases:
            reference = baseline[case["case_id"]]
            result = evaluate(case, capacity, honey_factor, varroa_scenario)
            for pattern in PATTERNS:
                if pattern == "SOLID_COMPACT":
                    continue
                alternative = evaluate(case, capacity, honey_factor, varroa_scenario, pattern)
                brood_checks += 1
                if (alternative["selected_class"], alternative["emitted_alerts"]) != (result["selected_class"], result["emitted_alerts"]):
                    raise ValueError("Brood-category assignment changed an emitted alert or selected class.")
            row = {**case, "scenario_id": f"C{capacity}_H{honey_factor}_{varroa_scenario}",
                   "capacity_frames": capacity, "kg_per_honey_frame": honey_factor,
                   "varroa_representation": varroa_scenario, **result,
                   "baseline_selected_class": reference["selected_class"],
                   "changed_selected_class": int(result["selected_class"] != reference["selected_class"]),
                   "changed_alert_set": int(result["emitted_alerts"] != reference["emitted_alerts"])}
            for name in ("swarming_score", "supersedure_score", "stress_score"):
                row["delta_" + name] = round(result[name] - reference[name], 3)
            group.append(row)
        case_rows.extend(group)
        summaries.append(summarize(group, capacity, honey_factor, varroa_scenario))
    rate_differences = [abs(c["mites_per_100_bees"] - 100*c["raw_varroa_count"]/c["associated_bee_count"]) for c in cases]
    report = {
        "source_dataset": {"doi": "10.5281/zenodo.21907271", "archive": "research/upstream_41521e6.zip",
                           "dataset_sha256": source_sha, "readme_url": README_URL,
                           "readme_md5": "d2bdc740b57a3e5417ee101d968b0032", "readme_retrieved": "2026-11-17"},
        "scorer_sha256_lf": hashlib.sha256((HERE/"analyze_beewise.py").read_bytes().replace(b"\r\n",b"\n")).hexdigest(),
        "input_sha256_lf": {n: hashlib.sha256((OUT/n).read_bytes().replace(b"\r\n",b"\n")).hexdigest()
                            for n in ("case_results.csv", "jvm_cases.csv")},
        "population": {"visits": len(cases), "colonies": len({c["hive_id"] for c in cases}),
                       "visits_by_round": dict(sorted(Counter(c["visit"] for c in cases).items()))},
        "design": {"reference": {"capacity_frames": 20, "kg_per_honey_frame": 2, "varroa_representation": "raw_count"},
                   "capacity_frames": CAPACITIES, "kg_per_honey_frame": HONEY_FACTORS,
                   "varroa_representations": VARROA_SCENARIOS, "factor_combinations": len(summaries),
                   "record_scenario_evaluations": len(case_rows), "additional_brood_assignment_checks": brood_checks,
                   "fixed_assignments": {"queen_age_years": 1, "brood_pattern": "SOLID_COMPACT",
                                         "queen_cells_count": 0, "queen_cells_placement": "NONE", "queen_cells_stage": "NONE",
                                         "pest_signs": False},
                   "scorer": "Imported scores(), predict(), kotlin_round(), and thresholds from analyze_beewise.py.",
                   "scaling": "Raw-count scenarios multiply by 0.5, 1 or 2. The unit-substitution scenario uses the recorded Mite_per_100 value. All four representations truncate to integer at the scorer input.",
                   "free_space": "100*(capacity-min(max(integer bee frames, integer brood frames)+honey frames,capacity))/capacity, rounded to two decimals. Honey kg is honey frames times its scenario factor, rounded to two decimals.",
                   "interpretation": "Deterministic paired sensitivity to declared adapter inputs. Unit substitution tests feeding a rate into the count field while retaining the fixed numerical rule thresholds.",
                   "uncertainty": "Counts enumerate these visits and assigned scenarios. No confidence intervals are calculated."},
        "unit_audit": {
            "documented_varroa_field": "Varroa is the number of mites detected. Mite_per_100 is mites per 100 adult bees.",
            "documented_honey_field": "Lieb_Honey is frames of honey estimated by the Liebefeld method.",
            "sample_count_columns_used_for_arithmetic_check": ["Bees_1", "Bees_2", "BeesAW_3"],
            "pairs_with_rate_and_positive_denominator": len(rate_differences),
            "associated_bee_count_min": min(c["associated_bee_count"] for c in cases),
            "associated_bee_count_max": max(c["associated_bee_count"] for c in cases),
            "rate_equals_100_count_over_associated_bee_count_within_0_005": sum(d <= .00500000001 for d in rate_differences),
            "maximum_absolute_rate_difference": max(rate_differences),
            "rate_min": min(c["mites_per_100_bees"] for c in cases), "rate_max": max(c["mites_per_100_bees"] for c in cases),
            "readme_omissions": ["sample-collection technique", "hive capacity in frames", "kilograms per honey frame", "numerical BroodPattern category mapping"],
        },
        "validation": {"existing_reference_fixture_cases_matched": len(baseline),
                       "jvm_fixture_path": "src/test/resources/adapter_sensitivity_jvm.csv",
                       "jvm_fixture_evaluations": len(case_rows),
                       "alert_sets_and_selected_classes_invariant_over_four_brood_categories_at_age_one": True},
        "scenarios": summaries,
    }
    write_csv(OUT / "adapter_sensitivity_cases.csv", case_rows)
    write_csv(OUT / "adapter_sensitivity_summary.csv", summaries)
    write_csv(HERE.parent / "src/test/resources/adapter_sensitivity_jvm.csv",
              [jvm_fixture(r, reference_fixtures[r["case_id"]]) for r in case_rows])
    (OUT / "adapter_sensitivity_summary.json").write_bytes((json.dumps(report, indent=2) + "\n").encode())
    print(json.dumps({"scenarios": len(summaries), "evaluations": len(case_rows),
                      "selected_stress_min": min(s["selected_stress"] for s in summaries),
                      "selected_stress_max": max(s["selected_stress"] for s in summaries)}, indent=2))


if __name__ == "__main__":
    main()
