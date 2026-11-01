# Inspection-data representation experiments

The analysis evaluates the effect of explicit data representations on a fixed rule engine. It preserves the original observations, source adapter, thresholds and scoring weights. Outputs describe computational agreement and sensitivity; they are not estimates of independently validated biological diagnosis accuracy.

## Run

Use Python 3.10 or later, with no third-party packages, from the repository root:

```sh
python research/analyze_beewise.py
sh ./gradlew test --no-daemon
python research/archive_verification.py
```

On Windows use `.\gradlew.bat` instead of `./gradlew`. The analysis reads `research/upstream_41521e6.zip`, copies its four source inputs to `research/upstream`, generates all tables in `research/outputs`, and writes the fixture to `src/test/resources/jvm_cases.csv`. The Kotlin tests have their own packaged fixture and can run independently of the analysis script.

To run only cross-language fixture verification:

```sh
sh ./gradlew test --tests '*BeewiseEvaluationBenchmarkTest' --no-daemon
```

Run the complete suite before archiving verification results. The full suite has **111 passing JUnit cases** (103 HTTP/PostgreSQL integration cases and 8 service cases). The two fixture tests check **6672 field-derived input configurations and 20 author-defined scenarios**, comparing all emitted alert types, emitted scores and selected output classes.

## Data and conventions

- Source snapshot: GitHub commit `41521e6`; the source adapter and engine are preserved as `.kt.txt` files under `upstream`.
- Dataset: [Beewise Colony Assessment 2025](https://doi.org/10.5281/zenodo.21907271); original CSV MD5 `4550fcf8b5de34d723a2662944f021e5`. Input SHA-256 values are recorded in `outputs/analysis_summary.json`.
- The source contains 120 hives and 360 scheduled hive-visit combinations. Adult-bee frames are parseable for 359 records. The quantitative-complete experiment excludes six further records with unavailable Varroa values and retains 353.
- The archived adapter truncates bee and brood frames, assumes 2 kg per honey frame and a 20-frame capacity, and supplies queen/pest values. These are representation conventions, not additional field measurements. Numerical rounding follows Kotlin `BigDecimal(double)` and HALF_UP.
- The source's numeric brood scale is preserved for the archived-adapter comparison. The primary quantitative experiment instead evaluates all four supported brood states as explicit counterfactual inputs.
- Removing note-derived queen-cell contributions sets their typed inputs to `NONE/0`. This disables a contribution to the engine; it is not evidence that a colony had no queen cells.
- Queen age is an explicit assumption. A separate grid combines ages 0, 1 and 2 with all four brood states for each of 353 records. It does not infer unobserved queen ages or brood states.
- The engine may emit several alerts. Field-derived selected-output summaries use SWARM, then SUPERSEDURE, then STRESS; otherwise NO_ALERT. Author scenarios retain their original SUPERSEDURE-first priority. Complete emitted-alert sets are reported separately.

## Confirmed results

- The archived-adapter output is reproduced exactly. Disabling note-derived queen-cell features changes 38 of the same 359 selected outputs.
- The 12 age/brood combinations are evaluated on the same 353 quantitative-complete records. At assumed age 0 or 1, selected outputs are invariant across the four brood states. Across the entire age/brood grid, no record has an invariant selected output; the age-2/drone-brood setting changes the supersedure contribution.
- Author-defined scenario-label agreement remains 19/20, including the preserved `SUP-08` disagreement. Cross-language fixture concordance remains complete: the Python transcription and Kotlin engine agree on 6692 configurations. These are different comparisons and should not be conflated.

## Outputs

| File in `outputs` | Contents |
|---|---|
| `analysis_summary.json` | Source hashes, missingness, exclusions, proxy matrices, emitted-alert counts and grid stability |
| `case_results.csv` | Per-case identifiers, visit dates, source variables, missingness, proxy rules, selected classes and emitted alerts |
| `sensitivity_summary.csv` | Four representation variants and descriptive proxy agreement |
| `note_ablation_changes.csv` | The 38 selected outputs changed by disabling note-derived morphology |
| `age_brood_grid.csv` | Counts for all 12 counterfactual age/brood combinations |
| `age_brood_case_predictions.csv` | Predictions for each of the 353 records across the grid |
| `stability_counts.json` | Invariance counts across brood and age assumptions |
| `age_sensitivity.csv` | Ages 0–3 under the archived brood heuristic, with/without note-derived cells |
| `author_scenarios.csv` | Original scenario labels/rationales, fixed-engine output and `SUP-08` disagreement |
| `jvm_cases.csv` | The 6692-row fixture, also copied into `src/test/resources` |
| `latex_tables.tex` | Generated representation, adapter-comparison and auxiliary proxy-agreement tables |
| `quantitative_excluded_cases.csv` | The six omitted records and their unavailable Varroa fields |

Proxy labels are computational rules based on note/exclusion keywords and bee-frame counts. Some of the same text supplies the archived morphology adapter. Their precision/recall/F1 outputs therefore describe rule agreement. The artifact preserves this development exercise for reproduction and reports representation sensitivity separately.
