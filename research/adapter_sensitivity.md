# Adapter sensitivity experiment

Run `python research/analyze_adapter_sensitivity.py` from the repository root, using Python 3.10 or later. The script uses the standard library and imports the existing scoring functions from `analyze_beewise.py`. Its reference inputs and outputs match all 353 compact-brood, age-one cases in `outputs/jvm_cases.csv`.

The experiment uses the same 353 complete visits from 120 colonies. Each visit is evaluated under 36 assigned combinations of 10, 20 or 30 frame capacity, 1, 2 or 3 kg per honey frame, and four representations of the Varroa input. The first three representations multiply the recorded mite count by 0.5, 1 or 2. The fourth substitutes the recorded mites-per-100-bees value into the count field. All representations truncate to an integer as required by the scorer's input. The reference combines 20 frames, 2 kg per honey frame and the recorded mite count. These values define a controlled range of adapter scenarios.

All scoring weights and cutoffs were selected heuristically by the original developer. Beewise observations also informed development of the stress rule, so this is a controlled representation experiment on development data. [Parameter provenance](rule_parameter_provenance.md) records that history and the distinction between raw counts and sampling-specific rates.

Queen age is assigned one year, queen-cell contributions are disabled, and pest signs are set to false. The reported scores use assigned compact brood. Additional calculations with each of the other three brood categories confirm that the emitted alerts and selected classes remain identical throughout this experiment. Brood-category scores remain below the supersedure threshold at the assigned age. All weights and numerical thresholds are imported from the existing transcription.

The free-space calculation retains the adapter's occupied-frame equivalent, integer truncation of bee and brood frames, cap at assigned capacity, and two-decimal rounding. Estimated occupation exceeds the assigned capacity for 80 visits at 10 frames, one visit at 20 frames and zero visits at 30 frames. The exported flag identifies each such case and the corresponding clamped free-space input.

## Source units

The dataset README defines `Lieb_Honey` as honey frames assessed by the Liebefeld method, `Varroa` as detected mite count, and `Mite_per_100` as mites per 100 adult bees. It provides no frame-capacity value, kilograms-per-frame conversion, numerical mapping of brood categories, or sample-collection technique. The retrieved README is archived as `outputs/adapter_sensitivity_dataset_readme.txt`, with its Zenodo MD5 recorded in the JSON report. [Zenodo README](https://zenodo.org/records/21907271/files/Beewise_Colony_Assessment_2025_README.txt?download=1)

For all 353 visits, the recorded rate agrees within 0.005 with 100 times the mite count divided by `Bees_1`, `Bees_2`, or `BeesAW_3` for the respective assessment. These associated bee counts range from 144 to 553. This arithmetic check documents the relationship of the stored fields. The rate-substitution scenario quantifies the effect of changing their input units while retaining the scorer's numerical thresholds.

## Executed results

The 36 combinations yield 12,708 record–scenario evaluations. The table gives stress alerts among 353 visits. Counts are identical at all three assigned capacities.

| kg per honey frame | Half raw count | Raw count | Double raw count | Recorded rate substituted |
|---|---:|---:|---:|---:|
| 1 | 22 | 54 | 100 | 8 |
| 2 | 3 | 8 | 16 | 1 |
| 3 | 1 | 3 | 6 | 0 |

At the reference 2 kg conversion, substituting the recorded rate changes seven selected outputs in seven colonies. Changing only the honey conversion to 1 kg changes 46 outputs in 39 colonies. Changing it to 3 kg changes five outputs in five colonies.

Changing only capacity from 20 to 10 frames changes swarming scores for 145 visits. Increasing it to 30 frames changes three scores. The resulting scores remain below the alert threshold, with zero swarming and supersedure alerts throughout this design. Counts and colony identifiers describe this fixed observed population. The report includes paired changes and affected-colony counts for every combination.

## Outputs

- `outputs/adapter_sensitivity_summary.json` records source hashes, units, formulas, fixed assignments, design, and scenario results.
- `outputs/adapter_sensitivity_summary.csv` contains one row per combination.
- `outputs/adapter_sensitivity_cases.csv` contains one row per visit and combination, with the raw fields, assigned inputs, scores, emitted alerts, selected class and paired changes.
- `src/test/resources/adapter_sensitivity_jvm.csv` contains all 12,708 evaluations in the existing JVM-fixture schema. The additional service test checks emitted alert sets, emitted scores and selected classes against the Kotlin scorer.

The script uses deterministic enumeration. The reported calculations have no confidence intervals or population-generalization step.
