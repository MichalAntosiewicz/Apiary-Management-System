#!/usr/bin/env python3
"""Reproduce an exploratory development exercise and audit input assumptions.

Standard library only. Run from any working directory:
    python research/analyze_beewise.py
Outputs are descriptive agreement with algorithmic proxy labels, NOT independent
biological accuracy. Model weights and source labels are never fitted here.
"""
from __future__ import annotations

import csv
import hashlib
import io
import json
import math
import platform
import shutil
import statistics
import zipfile
from collections import Counter
from datetime import datetime
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HERE = ROOT / "research"
OUT = HERE / "outputs"
UPSTREAM = HERE / "upstream"
CLASSES = ("REPRODUCTIVE_SWARM", "SUPERSEDURE", "COLONY_ABSCONDING", "NOMINAL")
PATTERNS = ("SOLID_COMPACT", "SPOTTY", "DRONE_BROOD_ONLY", "ABSENT")
THRESHOLDS = dict(zip(CLASSES[:3], (0.450, 0.550, 0.550)))
COMMIT = "41521e6"
ASSUMED_QUEEN_AGE = 1


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def original_files() -> dict[str, bytes]:
    """Read the immutable source archive used by the representation experiments."""
    names = ["Beewise_Colony_Assessment_2025_Data.csv", "literature_benchmark_20.csv",
             "BeewiseEvaluationBenchmarkTest.kt", "DecisionSupportEngine.kt"]
    archive = HERE / f"upstream_{COMMIT}.zip"
    with zipfile.ZipFile(archive) as z:
        result = {}
        for name in names:
            matches = [p for p in z.namelist() if p.endswith("/" + name) or p == name]
            if len(matches) != 1:
                raise ValueError(f"Expected one {name} in archive; found {matches}")
            result[name] = z.read(matches[0])
    for name, data in result.items():
        (UPSTREAM / (name + ".txt" if name.endswith(".kt") else name)).write_bytes(data)
    return result


def numeric(value: str) -> float | None:
    try:
        result = float(value)
        return result if math.isfinite(result) else None
    except (ValueError, TypeError):
        return None


def strict_integer(value: str) -> int | None:
    try:
        return int(value)
    except (ValueError, TypeError):
        return None


def kotlin_round(value: float, places: int) -> float:
    # Kotlin uses BigDecimal(double), not BigDecimal.valueOf(double).
    return float(Decimal.from_float(value).quantize(Decimal(1).scaleb(-places), rounding=ROUND_HALF_UP))


def numeric_pattern(raw: str) -> str:
    """Reproduce upstream heuristic; not a validated interpretation of scale."""
    n = numeric(raw)
    if n is None:
        return "SOLID_COMPACT"
    if n == 0:
        return "ABSENT"
    return "SPOTTY" if 0 < n < 4 else "SOLID_COMPACT"


def proxy_label(notes: str, au: str, bees: int) -> tuple[str, str, str]:
    combined = notes + " " + au
    if "swarm" in combined:
        sources = "+".join(k for k, v in [("notes", notes), ("au_reason", au)] if "swarm" in v)
        return CLASSES[0], "swarm_keyword", sources
    for term in ("abscond", "deadout", "dead", "empty hive"):
        if term in combined:
            return CLASSES[2], "loss_keyword:" + term, "notes_or_exclusion_reason"
    queen_terms = ("supersedure", "new queen", "nq", "ql", "queenless", "virginq", "qcell", "queen cell")
    for term in queen_terms:
        if term in combined:
            return (CLASSES[2] if bees < 3 else CLASSES[1],
                    "queen_keyword:" + term + (";bees<3" if bees < 3 else ";bees>=3"),
                    "notes_or_exclusion_reason+current_bee_frames")
    for term in ("foul brood", "excluded", "weak", "infestation"):
        if term in combined:
            return CLASSES[2], "problem_keyword:" + term, "notes_or_exclusion_reason"
    return CLASSES[3], "default_no_keyword_match", "default_not_confirmed_nominal"


def scores(x: dict, age: int) -> dict[str, float]:
    swm = 0.0
    if x["placement"] == "MARGINAL":
        swm += 0.40 if x["cell_count"] >= 6 else 0.25 if 1 <= x["cell_count"] <= 5 else 0.0
        swm += 0.20 if x["stage"] == "SEALED" else 0.10 if x["stage"] == "OPEN_WITH_LARVA" else 0.0
    swm += 0.25 if x["space"] <= 10 else 0.15 if x["space"] <= 20 else 0.0
    swm += 0.10 if x["bees"] >= 10 and x["brood"] >= 6 else 0.0
    swm += 0.10 if age >= 2 else 0.0
    sup = 0.50 if x["placement"] == "CENTRAL" and 1 <= x["cell_count"] <= 4 else 0.0
    sup += {"SPOTTY": 0.30, "DRONE_BROOD_ONLY": 0.40, "ABSENT": 0.20, "SOLID_COMPACT": -0.20}[x["pattern"]]
    sup += 0.15 if age >= 2 else 0.0
    abs_ = 0.35 if x["varroa"] >= 25 else 0.20 if x["varroa"] >= 12 else 0.0
    abs_ += 0.40 if x["honey"] < 1.50 else 0.20 if x["honey"] < 2.50 else 0.0
    abs_ += 0.25 if x["pests"] else 0.0
    if x["varroa"] >= 12 and x["honey"] < 2.50:
        abs_ += 0.20
    return dict(zip(CLASSES[:3], [kotlin_round(min(1.0, max(0.0, s)), 3) for s in (swm, sup, abs_)]))


def predict(s: dict, priority: tuple = CLASSES[:3]) -> str:
    return next((c for c in priority if s[c] >= THRESHOLDS[c]), CLASSES[3])


def agreement(records: list[dict]) -> dict:
    matrix = [[0] * 4 for _ in range(4)]
    for record in records:
        matrix[CLASSES.index(record["proxy_label"])][CLASSES.index(record["predicted"])] += 1
    per_class = {}
    for i, c in enumerate(CLASSES):
        support = sum(matrix[i])
        predicted = sum(row[i] for row in matrix)
        tp = matrix[i][i]
        per_class[c] = dict(support=support, predicted=predicted,
                            precision=tp / predicted if predicted else 0.0,
                            recall=tp / support if support else 0.0,
                            f1=2 * tp / (support + predicted) if support + predicted else None)
    active = [m["f1"] for m in per_class.values() if m["f1"] is not None]
    return dict(n=len(records), matrix=matrix, class_order=CLASSES, per_class=per_class,
                matching=sum(matrix[i][i] for i in range(4)),
                agreement=sum(matrix[i][i] for i in range(4)) / len(records),
                macro_f1=sum(active) / len(active), macro_class_count=len(active))


def write_csv(path: Path, records: list[dict]) -> None:
    with path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(records[0]))
        writer.writeheader()
        writer.writerows(records)


def latex_tables(summary: dict, grid: list[dict]) -> str:
    """Publication tables are generated from executed outputs, never typed scores."""
    v = summary["variants"]
    d0, d1 = v["legacy_note_features"], v["no_note_features"]
    lines = [r"% Generated by research/analyze_beewise.py; requires booktabs.",
             r"% Tables 1--2 belong in the main results; table 3 is auxiliary/appendix.",
             r"\begin{table}[htbp]", r"\centering\small",
             r"\caption{Counterfactual age and brood-state sweep with note-derived queen-cell contributions disabled. Each input combination is evaluated on the same 353 records. Counts are selected outputs, not observed colony outcomes.}",
             r"\label{tab:representation-grid}",
             r"\begin{tabular}{llrrrrr}\toprule",
             r"Age & Brood state & $N$ & SWM & SUP & STR & No alert\\\midrule"]
    for label, brood, row in [("0 or 1", "Any of four states", grid[0]),
                               ("2", "Solid, spotty, or absent", grid[8]),
                               ("2", "Drone brood only", grid[10])]:
        nums = [row[k] for k in ("n", "selected_SWARM", "selected_SUPERSEDURE", "selected_STRESS", "selected_NO_ALERT")]
        lines.append(label + " & " + brood + " & " + " & ".join(map(str, nums)) + r"\\")
    lines.extend([r"\bottomrule\end{tabular}",
                  r"\par\smallskip\footnotesize SWM: swarming alert; SUP: supersedure alert; STR: stress alert (internal identifier COLONY\_ABSCONDING). In the last row, eight stress alerts are also emitted; the supersedure alert has higher selection priority.",
                  r"\end{table}", "", r"\begin{table}[htbp]", r"\centering\small",
                  r"\caption{Controlled removal of note-derived queen-cell features, with fixed rules and the same 359 inspection records. Changed denotes selected outputs differing from the archived adapter.}",
                  r"\label{tab:adapter-ablation}",
                  r"\begin{tabular}{lrrrrrrrrr}\toprule",
                  r" & & \multicolumn{4}{c}{Selected output} & \multicolumn{3}{c}{Emitted alerts} & \\",
                  r"\cmidrule(lr){3-6}\cmidrule(lr){7-9}",
                  r"Adapter & $N$ & SWM & SUP & STR & None & SWM & SUP & STR & Changed\\\midrule"])
    for name, row, changes in [("Archived", d0, 0), ("Note features disabled", d1, summary["changed_predictions_when_note_features_removed"])]:
        nums = [row["n"]] + [row["per_class"][c]["predicted"] for c in CLASSES] + [row["emitted_alert_counts"][c] for c in CLASSES[:3]] + [changes]
        lines.append(name + " & " + " & ".join(map(str, nums)) + r"\\")
    lines.extend([r"\bottomrule\end{tabular}",
                  r"\par\smallskip\footnotesize Selection priority is SWM, then SUP, then STR; None denotes no emitted alert. The archived adapter has one simultaneous SWM/STR case. The 38 changed selected outputs represent 10.58\% of the records.",
                  r"\end{table}", "", r"\begin{table}[htbp]", r"\centering\small",
                  r"\caption{Reproduction of the archived development exercise against its algorithmic proxy labels. This table measures rule agreement; the labels are not independent field diagnoses.}",
                  r"\label{tab:proxy-reproduction}",
                  r"\begin{tabular}{lrrrr}\toprule",
                  r"Proxy label / selected output & SWM & SUP & ABS & NOM\\\midrule"])
    for name, row in zip(("SWM", "SUP", "ABS", "NOM"), d0["matrix"]):
        lines.append(name + " & " + " & ".join(map(str, row)) + r"\\")
    lines.extend([r"\bottomrule\end{tabular}",
                  rf"\par\smallskip\footnotesize Agreement: {d0['matching']}/{d0['n']} ({d0['agreement']*100:.2f}\%); macro $F_1$ over four classes: {d0['macro_f1']:.6f}. Labels and their generation rules are retained for reproducibility only.",
                  r"\end{table}", ""])
    return "\n".join(lines)


def fixture(case_id: str, variant: str, date: str, x: dict, age: int,
            label: str, s: dict, priority: tuple = CLASSES[:3]) -> dict:
    return dict(case_id=case_id, variant=variant, inspection_date=date, queen_age_assumed=age,
                frames_of_bees=x["bees"], frames_of_brood=x["brood"], honey_kg=f'{x["honey"]:.2f}',
                free_space_percent=f'{x["space"]:.2f}', brood_pattern=x["pattern"],
                queen_cells_count=x["cell_count"], queen_cells_placement=x["placement"],
                queen_cells_stage=x["stage"], varroa_count=x["varroa"], signs_of_pests=str(x["pests"]).lower(),
                proxy_or_scenario_label=label, expected_prediction=predict(s, priority),
                swarming_score=f'{s[CLASSES[0]]:.3f}', supersedure_score=f'{s[CLASSES[1]]:.3f}',
                absconding_score=f'{s[CLASSES[2]]:.3f}', priority=";".join(priority))


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    UPSTREAM.mkdir(parents=True, exist_ok=True)
    inputs = original_files()
    raw_bytes = inputs["Beewise_Colony_Assessment_2025_Data.csv"]
    reader = csv.DictReader(io.StringIO(raw_bytes.decode("utf-8-sig")))
    headers = reader.fieldnames
    rows = list(reader)
    raw_patterns, missing, labels = Counter(), Counter(), Counter()
    cases, skipped = [], []
    for line_no, row in enumerate(rows, 2):
        for visit in (1, 2, 3):
            get = lambda field: (row.get(f"{field}_{visit}") or "").strip()
            case_id = row["hive"] + f"-V{visit}"
            raw_bees = numeric(get("Lieb_Bees"))
            if raw_bees is None:
                skipped.append(dict(case_id=case_id, source_row=line_no, reason="missing_or_unparseable_Lieb_Bees"))
                continue
            raw_values = {k: get(k) for k in ("Lieb_Bees", "Lieb_WorkerBrood", "Lieb_DroneBrood", "Lieb_Honey", "Varroa", "BroodPattern")}
            raw_patterns[get("BroodPattern")] += 1
            absent = [k for k, value in raw_values.items() if (strict_integer(value) if k == "Varroa" else numeric(value)) is None]
            missing.update(absent)
            bees = int(raw_bees)
            brood = int((numeric(get("Lieb_WorkerBrood")) or 0) + (numeric(get("Lieb_DroneBrood")) or 0))
            honey_frames = numeric(get("Lieb_Honey")) or 0
            notes, au = get("Notes").lower(), (row.get(f"AU_reason_As{visit}") or "").strip().lower()
            label, label_rule, source_tags = proxy_label(notes, au, bees)
            labels[label_rule] += 1
            placement, stage, cells, morphology_source = "NONE", "NONE", 0, "no_keyword_default"
            if "swarm" in notes:
                placement, stage, cells, morphology_source = "MARGINAL", "SEALED", 7, "notes_swarm_synthetic"
            elif any(term in notes for term in ("ql", "nq", "queenless", "virginq", "qcell", "queen cell", "supersedure")):
                placement, stage, cells, morphology_source = "CENTRAL", "OPEN_WITH_LARVA", 2, "notes_queen_synthetic"
            # These are source dates, not evidence that event timing within a note matches the visit.
            date = datetime.strptime(get("InspectionDate"), "%m/%d/%Y").date().isoformat()
            x = dict(bees=bees, brood=brood, honey=kotlin_round(honey_frames * 2.0, 2),
                     space=kotlin_round((20.0 - min(max(bees, brood) + honey_frames, 20.0)) / 20.0 * 100.0, 2),
                     varroa=strict_integer(get("Varroa")) or 0, pattern=numeric_pattern(get("BroodPattern")),
                     placement=placement, stage=stage, cell_count=cells, pests=False)
            cases.append(dict(case_id=case_id, source_row=line_no, hive_id=row["hive"], visit=visit,
                              inspection_date=date, proxy_label=label, label_rule=label_rule,
                              label_source_tags=source_tags, notes=notes, au_reason=au,
                              brood_pattern_raw=get("BroodPattern"), missing_fields=";".join(absent),
                              morphology_source=morphology_source, raw_values=raw_values, features=x))
    variants = {}
    fixtures, records = [], []
    for variant in ("legacy_note_features", "no_note_features", "no_note_numeric_complete", "brood_agnostic_complete"):
        included = []
        exclusions = Counter()
        for case in cases:
            x = dict(case["features"])
            absent = case["missing_fields"].split(";") if case["missing_fields"] else []
            pattern_n = numeric(case["brood_pattern_raw"])
            if variant == "no_note_numeric_complete" and (absent or pattern_n is None or not 0 <= pattern_n <= 5):
                exclusions["incomplete_numeric_inputs_or_brood"] += 1
                continue
            if variant == "brood_agnostic_complete" and any(k != "BroodPattern" for k in absent):
                exclusions["incomplete_quantitative_inputs"] += 1
                continue
            if variant != "legacy_note_features":
                # Feature ablation: NONE/0 is a disabled contribution, not an observed absence of cells.
                x.update(placement="NONE", stage="NONE", cell_count=0)
            s = scores(x, ASSUMED_QUEEN_AGE)
            sup_score_bounds = [s[CLASSES[1]]]
            if variant == "brood_agnostic_complete":
                possible = set()
                sup_score_bounds = []
                for pattern in PATTERNS:
                    alternative = dict(x, pattern=pattern)
                    bound_scores = scores(alternative, ASSUMED_QUEEN_AGE)
                    possible.add(predict(bound_scores))
                    sup_score_bounds.append(bound_scores[CLASSES[1]])
                    fixtures.append(fixture(case["case_id"], variant + "__" + pattern, case["inspection_date"],
                                            alternative, ASSUMED_QUEEN_AGE, case["proxy_label"], bound_scores))
                if len(possible) != 1:
                    raise AssertionError("Unresolved brood interpretation: report prediction sets instead of selecting a favorable state")
            else:
                fixtures.append(fixture(case["case_id"], variant, case["inspection_date"], x,
                                        ASSUMED_QUEEN_AGE, case["proxy_label"], s))
            pred = predict(s)
            record = {k: v for k, v in case.items() if k not in ("raw_values", "features")}
            record.update(variant=variant, queen_age_assumed=ASSUMED_QUEEN_AGE,
                          features_status="legacy_heuristics" if variant == "legacy_note_features" else "note_morphology_contribution_disabled",
                          predicted=pred, swarming_score=s[CLASSES[0]],
                          supersedure_score=None if variant == "brood_agnostic_complete" else s[CLASSES[1]],
                          supersedure_score_min=min(sup_score_bounds), supersedure_score_max=max(sup_score_bounds),
                          absconding_score=s[CLASSES[2]],
                          emitted_alert_types=";".join(c for c in CLASSES[:3] if s[c] >= THRESHOLDS[c]),
                          mapped_brood_pattern="ALL_FOUR_SAME_PREDICTION" if variant == "brood_agnostic_complete" else x["pattern"],
                          matches_proxy=pred == case["proxy_label"], **{f"raw_{k}": v for k, v in case["raw_values"].items()})
            records.append(record)
            included.append(record)
        variants[variant] = {**agreement(included), "exclusions": dict(exclusions),
                             "emitted_alert_counts": {c: sum(c in r["emitted_alert_types"].split(";") for r in included) for c in CLASSES[:3]},
                             "cases_with_multiple_alerts": sum(";" in r["emitted_alert_types"] for r in included)}
    legacy = [r for r in records if r["variant"] == "legacy_note_features"]
    no_notes = {r["case_id"]: r for r in records if r["variant"] == "no_note_features"}
    changed = [dict(case_id=r["case_id"], inspection_date=r["inspection_date"], proxy_label=r["proxy_label"],
                    legacy_prediction=r["predicted"], no_note_prediction=no_notes[r["case_id"]]["predicted"],
                    morphology_source=r["morphology_source"], label_rule=r["label_rule"],
                    notes=r["notes"], au_reason=r["au_reason"]) for r in legacy if r["predicted"] != no_notes[r["case_id"]]["predicted"]]
    # This check freezes an observed upstream development result, not a biological target.
    expected_legacy = [[20, 1, 0, 17], [0, 13, 0, 31], [0, 4, 3, 20], [0, 0, 4, 246]]
    assert variants["legacy_note_features"]["matrix"] == expected_legacy

    scenario_records = []
    for row in csv.DictReader(io.StringIO(inputs["literature_benchmark_20.csv"].decode("utf-8-sig"))):
        x = dict(bees=int(row["framesOfBees"]), brood=int(row["framesOfBrood"]), honey=float(row["honeyStoresKg"]),
                 space=float(row["freeCombSpacePercent"]), varroa=int(row["varroaMiteCount"]), pattern=row["broodPattern"],
                 placement=row["queenCellsPlacement"], stage=row["queenCellsStage"], cell_count=int(row["queenCellsCount"]),
                 pests=row["signsOfPests"] == "true")
        age = int(row["queenAgeYears"])
        s = scores(x, age)
        priority = (CLASSES[1], CLASSES[0], CLASSES[2])  # preserve upstream B convention explicitly
        pred = predict(s, priority)
        fixtures.append(fixture(row["scenarioId"], "author_scenarios", "2025-03-24", x, age, row["expectedClass"], s, priority))
        scenario_records.append(dict(case_id=row["scenarioId"], proxy_label=row["expectedClass"], predicted=pred,
                                     matches_scenario=pred == row["expectedClass"], rationale=row["biologicalRationale"],
                                     rationale_status="author_defined_not_independently_adjudicated", swarming_score=s[CLASSES[0]],
                                     supersedure_score=s[CLASSES[1]], absconding_score=s[CLASSES[2]]))
    assert [r["case_id"] for r in scenario_records if not r["matches_scenario"]] == ["SUP-08"]

    age_sensitivity = []
    for age in (0, 1, 2, 3):
        for remove_notes in (False, True):
            sample = []
            for case in cases:
                x = dict(case["features"])
                if remove_notes:
                    x.update(placement="NONE", stage="NONE", cell_count=0)
                sample.append(dict(proxy_label=case["proxy_label"], predicted=predict(scores(x, age))))
            m = agreement(sample)
            age_sensitivity.append(dict(queen_age_assumed=age, note_features="disabled" if remove_notes else "legacy",
                                        n=m["n"], matching=m["matching"], agreement=m["agreement"], macro_f1=m["macro_f1"],
                                        swarm_alerts=m["per_class"][CLASSES[0]]["predicted"],
                                        supersedure_alerts=m["per_class"][CLASSES[1]]["predicted"]))

    # Counterfactual grid: none of these ages or categorical brood states is inferred from the data.
    complete = [c for c in cases if not any(k != "BroodPattern" for k in c["missing_fields"].split(";") if k)]
    grid, by_case_grid = [], {c["case_id"]: {} for c in complete}
    for age in (0, 1, 2):
        for pattern in PATTERNS:
            selected_counts, emitted_counts = Counter(), Counter()
            for case in complete:
                x = dict(case["features"], placement="NONE", stage="NONE", cell_count=0, pattern=pattern)
                s = scores(x, age)
                selected_counts[predict(s)] += 1
                emitted_counts.update(c for c in CLASSES[:3] if s[c] >= THRESHOLDS[c])
                by_case_grid[case["case_id"]][f"age{age}_{pattern}"] = predict(s)
                fixtures.append(fixture(case["case_id"], f"age_brood_grid__age_{age}__{pattern}", case["inspection_date"], x, age, case["proxy_label"], s))
            grid.append(dict(queen_age_assumed=age, hypothetical_brood_pattern=pattern, n=len(complete),
                             selected_SWARM=selected_counts[CLASSES[0]], selected_SUPERSEDURE=selected_counts[CLASSES[1]],
                             selected_STRESS=selected_counts[CLASSES[2]], selected_NO_ALERT=selected_counts[CLASSES[3]],
                             emitted_SWARM=emitted_counts[CLASSES[0]], emitted_SUPERSEDURE=emitted_counts[CLASSES[1]], emitted_STRESS=emitted_counts[CLASSES[2]]))
    stability = dict(n=len(complete), stable_over_entire_age_brood_grid=sum(len(set(p.values())) == 1 for p in by_case_grid.values()),
                     stable_over_brood_states_by_age={str(age): sum(len({pred for key,pred in p.items() if key.startswith(f"age{age}_")}) == 1 for p in by_case_grid.values()) for age in (0,1,2)},
                     interpretation="Counterfactual coverage of missing inputs, not observed queen ages or biological brood states")
    write_csv(OUT / "age_brood_grid.csv", grid)
    (OUT / "stability_counts.json").write_text(json.dumps(stability, indent=2) + "\n", encoding="utf-8")
    write_csv(OUT / "age_brood_case_predictions.csv", [dict(case_id=k, **v) for k,v in by_case_grid.items()])
    majority = agreement([dict(proxy_label=r["proxy_label"], predicted=CLASSES[3]) for r in legacy])
    varroa_values = [strict_integer(c["raw_values"]["Varroa"]) for c in cases if strict_integer(c["raw_values"]["Varroa"]) is not None]
    quantitative_excluded = [dict(case_id=c["case_id"], source_row=c["source_row"], inspection_date=c["inspection_date"],
                                 missing_fields=c["missing_fields"], raw_varroa=c["raw_values"]["Varroa"]) for c in cases if c not in complete]
    summary = dict(source_commit=COMMIT, dataset_doi="10.5281/zenodo.21907271", dataset_md5=hashlib.md5(raw_bytes).hexdigest(),
                   input_sha256={k: sha256(v) for k, v in inputs.items()}, python_version=platform.python_version(),
                   data_rows=len(rows), unique_hives=len({r["hive"] for r in rows}), candidate_visits=len(rows) * 3,
                   analyzed_visits=len(cases), skipped_visits=skipped,
                   assessment_date_counts={str(v): dict(Counter(c["inspection_date"] for c in cases if c["visit"] == v)) for v in (1,2,3)},
                   duplicate_csv_headers=[k for k, v in Counter(headers).items() if v > 1],
                   raw_brood_values=dict(raw_patterns), missing_or_unparseable=dict(missing), proxy_label_rules=dict(labels),
                   quantitative_excluded_cases=quantitative_excluded,
                   raw_varroa_count_summary=dict(n=len(varroa_values), minimum=min(varroa_values), maximum=max(varroa_values),
                                                 median=statistics.median(varroa_values), zeros=varroa_values.count(0),
                                                 measurement="Raw integer Varroa assessment count; not normalized per 100 sampled bees"),
                   assumed_queen_age_years=ASSUMED_QUEEN_AGE, variants=variants,
                   changed_predictions_when_note_features_removed=len(changed),
                   swarm_notes=sum("swarm" in c["notes"] for c in cases),
                   swarm_proxy_labels=sum(c["proxy_label"] == CLASSES[0] for c in cases),
                   nominal_with_empty_notes_and_reason=sum(c["proxy_label"] == CLASSES[3] and not c["notes"] and not c["au_reason"] for c in cases),
                   majority_label_reference=majority, author_scenarios=agreement(scenario_records),
                   author_scenario_mismatches=[r["case_id"] for r in scenario_records if not r["matches_scenario"]],
                   age_brood_grid_stability=stability,
                   jvm_fixture_rows=len(fixtures), interpretation="Descriptive proxy-label agreement; no held-out biological validation")
    (OUT / "analysis_summary.json").write_text(json.dumps(summary, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    (OUT / "latex_tables.tex").write_text(latex_tables(summary, grid), encoding="utf-8")
    write_csv(OUT / "quantitative_excluded_cases.csv", quantitative_excluded)
    write_csv(OUT / "case_results.csv", records)
    write_csv(OUT / "note_ablation_changes.csv", changed)
    write_csv(OUT / "author_scenarios.csv", scenario_records)
    write_csv(OUT / "age_sensitivity.csv", age_sensitivity)
    write_csv(OUT / "jvm_cases.csv", fixtures)
    shutil.copyfile(OUT / "jvm_cases.csv", ROOT / "src/test/resources/jvm_cases.csv")
    write_csv(OUT / "sensitivity_summary.csv", [dict(variant=v, n=m["n"], matching=m["matching"], agreement=m["agreement"], macro_f1=m["macro_f1"],
                                                     swarm_alerts=m["per_class"][CLASSES[0]]["predicted"],
                                                     supersedure_alerts=m["per_class"][CLASSES[1]]["predicted"],
                                                     absconding_alerts=m["per_class"][CLASSES[2]]["predicted"]) for v, m in variants.items()])
    print(json.dumps({"variants": variants, "changed_predictions": len(changed), "missing": dict(missing),
                      "jvm_fixture_rows": len(fixtures), "author_scenario_agreement": summary["author_scenarios"]["agreement"]}, indent=2))


if __name__ == "__main__":
    main()
