#!/usr/bin/env python3
"""Summarize paired inspection outputs with a colony-cluster percentile bootstrap.

Run with Python 3.10+ from any directory. Inputs and outputs are under research/outputs.
"""
from __future__ import annotations

import csv
import hashlib
import json
import random
from collections import Counter, defaultdict
from pathlib import Path


OUT = Path(__file__).resolve().parent / "outputs"
VARIANTS = ("legacy_note_features", "no_note_features")
CLASSES = ("REPRODUCTIVE_SWARM", "SUPERSEDURE", "COLONY_ABSCONDING", "NOMINAL")
SCORER_INPUTS = (
    "queen_age_assumed", "frames_of_bees", "frames_of_brood", "honey_kg",
    "free_space_percent", "brood_pattern", "queen_cells_count", "queen_cells_placement",
    "queen_cells_stage", "varroa_count", "signs_of_pests",
)
SEED = 20261117
REPLICATES = 20_000


def read_csv(name):
    with (OUT / name).open(encoding="utf-8-sig", newline="") as stream:
        return list(csv.DictReader(stream))


def paired_visits(records):
    by_variant = {v: {} for v in VARIANTS}
    for row in records:
        v, case = row["variant"], row["case_id"]
        if v in by_variant:
            if case in by_variant[v]:
                raise ValueError(f"Duplicate {v} visit {case}")
            by_variant[v][case] = row
    original, ablated = (by_variant[v] for v in VARIANTS)
    if original.keys() != ablated.keys() or not original:
        raise ValueError("Variants must contain the same non-empty set of visits.")
    visits = []
    for case in sorted(original):
        left, right = original[case], ablated[case]
        fields = ("case_id", "hive_id", "visit", "inspection_date", "proxy_label")
        if any(left[k] != right[k] for k in fields):
            raise ValueError(f"Paired metadata disagree for {case}")
        visits.append({**{k: left[k] for k in fields},
                       "note_derived": left["predicted"], "note_features_disabled": right["predicted"],
                       "changed": int(left["predicted"] != right["predicted"])})
    return visits


def metric_values(colonies):
    matrices = [[0] * 16, [0] * 16]
    changed_visits, changed_colonies = 0, 0
    for colony in colonies:
        colony_changes = 0
        for note_cell, disabled_cell in colony:
            matrices[0][note_cell] += 1
            matrices[1][disabled_cell] += 1
            colony_changes += note_cell != disabled_cell
        changed_visits += colony_changes
        changed_colonies += colony_changes > 0
    n = sum(matrices[0])
    metrics = {"changed_visit_fraction": changed_visits / n,
               "fraction_of_colonies_with_a_changed_visit": changed_colonies / len(colonies)}
    for name, matrix in zip(VARIANTS, matrices):
        diagonal = [matrix[5*i] for i in range(4)]
        denominators = [sum(matrix[4*i:4*i+4]) + sum(matrix[i::4]) for i in range(4)]
        metrics[name + "_agreement"] = sum(diagonal) / n
        metrics[name + "_macro_f1"] = sum(2*t/d if d else 0 for t,d in zip(diagonal, denominators)) / 4
    for metric in ("agreement", "macro_f1"):
        metrics["disabled_minus_note_derived_" + metric] = (
            metrics[VARIANTS[1] + "_" + metric] - metrics[VARIANTS[0] + "_" + metric])
    return metrics, matrices


def percentile(sorted_values, probability):
    position = (len(sorted_values) - 1) * probability
    lower = int(position)
    return sorted_values[lower] + (position - lower) * (sorted_values[min(lower + 1, len(sorted_values)-1)] - sorted_values[lower])


def clustered_intervals(visits):
    by_hive = defaultdict(list)
    for visit in visits:
        row_offset = 4 * CLASSES.index(visit["proxy_label"])
        by_hive[visit["hive_id"]].append(tuple(
            row_offset + CLASSES.index(visit[k]) for k in ("note_derived", "note_features_disabled")))
    colonies = [by_hive[k] for k in sorted(by_hive)]
    point, matrices = metric_values(colonies)
    draws = {k: [] for k in point}
    rng = random.Random(SEED)
    for _ in range(REPLICATES):
        sample = rng.choices(colonies, k=len(colonies))
        metrics, _ = metric_values(sample)
        for key, value in metrics.items():
            draws[key].append(value)
    estimates = {}
    for key, sample_values in draws.items():
        sample_values.sort()
        estimates[key] = {"estimate": point[key], "lower_95": percentile(sample_values, .025),
                          "upper_95": percentile(sample_values, .975)}
    return estimates, {v: [m[i:i+4] for i in range(0,16,4)] for v,m in zip(VARIANTS, matrices)}


def fixture_counts(fixtures, visits):
    expected = {v["case_id"]: v for v in visits}
    field = [r for r in fixtures if r["variant"] != "author_scenarios"]
    groups = Counter(r["variant"].split("__")[0] for r in field)
    for variant, output in zip(VARIANTS, ("note_derived", "note_features_disabled")):
        rows = [r for r in field if r["variant"] == variant]
        if len(rows) != len(expected) or set(expected) != {r["case_id"] for r in rows}:
            raise ValueError(f"Inconsistent fixture visit IDs for {variant}")
        for row in rows:
            visit = expected[row["case_id"]]
            if (row["expected_prediction"], row["proxy_or_scenario_label"], row["inspection_date"]) != (
                    visit[output], visit["proxy_label"], visit["inspection_date"]):
                raise ValueError(f"Fixture and record differ for {variant} {row['case_id']}")
    key_fields = ("case_id", "inspection_date", *SCORER_INPUTS)
    def visit_input_keys(rows):
        return {tuple(r[k] for k in key_fields) for r in rows}
    distinct = len(visit_input_keys(field))
    brood = visit_input_keys(r for r in field if r["variant"].startswith("brood_agnostic_complete__"))
    grid_age_one = visit_input_keys(r for r in field if r["variant"].startswith("age_brood_grid__age_1__"))
    if not brood or brood != grid_age_one:
        raise ValueError("Age-one grid does not reproduce the four-pattern complete-record fixtures.")
    return {"field_evaluations_by_group": dict(groups), "field_evaluations": len(field),
            "author_defined_scenarios": len(fixtures) - len(field), "total_evaluations": len(fixtures),
            "age_brood_grid_visits": len({r["case_id"] for r in field if r["variant"].startswith("age_brood_grid__")}),
            "evaluations_within_age_brood_grid": groups["age_brood_grid"],
            "distinct_visit_input_pairs": distinct, "repeated_field_evaluations": len(field)-distinct,
            "deduplication_key": list(key_fields),
            "age_one_grid_pairs_also_in_four_pattern_group": len(brood)}


def main():
    visits = paired_visits(read_csv("case_results.csv"))
    colonies = Counter(v["hive_id"] for v in visits)
    changes = [v for v in visits if v["changed"]]
    changes_by_colony = Counter(v["hive_id"] for v in changes)
    rounds = sorted({v["visit"] for v in visits}, key=int)
    estimates, matrices = clustered_intervals(visits)
    case_output = OUT / "clustered_paired_visits.csv"
    fixture_summary = fixture_counts(read_csv("jvm_cases.csv"), visits)
    report = {
        "inputs_sha256": {n: hashlib.sha256((OUT/n).read_bytes().replace(b"\r\n", b"\n")).hexdigest()
                          for n in ("case_results.csv", "jvm_cases.csv")},
        "input_hash_convention": "UTF-8 CSV bytes with CRLF converted to LF",
        "paired_case_output": case_output.name,
        "retained_visits": len(visits), "unique_colonies": len(colonies),
        "colonies_by_number_of_retained_visits": dict(sorted(Counter(colonies.values()).items())),
        "changed_visits": len(changes), "colonies_with_a_changed_visit": len(changes_by_colony),
        "colonies_by_number_of_changed_visits": dict(sorted(Counter(changes_by_colony[h] for h in colonies).items())),
        "by_assessment_round": {r: {"retained_visits": sum(v["visit"] == r for v in visits),
                                    "changed_visits": sum(v["visit"] == r for v in changes)} for r in rounds},
        "changed_selected_classes_note_derived_to_disabled": [
            {"from": a, "to": b, "visits": n} for (a,b),n in sorted(
                Counter((v["note_derived"],v["note_features_disabled"]) for v in changes).items())],
        "class_order": list(CLASSES), "internal_proxy_rows_by_selected_output_columns": matrices,
        "bootstrap": {
            "method": "Paired nonparametric colony-cluster percentile bootstrap",
            "replicates": REPLICATES, "seed": SEED,
            "sampling": "Python random.Random(seed).choices: sample the observed number of colonies uniformly with replacement from sorted hive IDs. Keep every available paired visit in each drawn colony, including repeated draws.",
            "estimands": "Pool all sampled visits for output changes, internal proxy agreement and macro-F1. Use one indicator per sampled colony for at least one changed visit. Both adapter variants use the same draws.",
            "macro_f1": "Unweighted mean across four fixed classes, F1 = 2TP/(proxy support + predicted count), zero when both counts are zero.",
            "interval": "2.5% and 97.5% empirical quantiles with linear interpolation at (B-1)p.",
            "interpretation": "Intervals describe between-colony sampling variation in these paired records. The text-derived inputs and proxy labels share source material; proxy agreement measures internal consistency.",
            "estimates_and_intervals": estimates,
        },
        "fixture_evaluations": fixture_summary,
    }
    with case_output.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(visits[0]), lineterminator="\n")
        writer.writeheader()
        writer.writerows(visits)
    (OUT / "clustered_record_summary.json").write_bytes((json.dumps(report, indent=2) + "\n").encode("utf-8"))
    print(f"{len(visits)} visits, {len(colonies)} colonies, {len(changes)} changed visits, {REPLICATES} bootstrap samples.")


if __name__ == "__main__":
    main()
