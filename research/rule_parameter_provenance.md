# Advisory parameter provenance

Michał Antosiewicz, the system's original developer and first author, confirmed the development procedure on 18 November 2026. Biological literature informed the choice of observation groups. He selected all numerical weights, condition cutoffs and emission thresholds heuristically during implementation. There was no beekeeper consultation or formal expert-elicitation procedure. The references below provide biological and measurement context; the numerical parameter values are author-defined implementation choices.

## Complete numerical configuration

Every numerical value in the following table has **author-defined heuristic** provenance. The stress/absconding group additionally used Beewise observations during development, as described below. Mutually exclusive ranges contribute once.

| Rule | Condition | Score contribution or threshold |
|---|---|---|
| Swarming | Marginal queen cells >=6 / 1-5 | +0.40 / +0.25 |
| Swarming | Marginal cells sealed / open with larva | +0.20 / +0.10 |
| Swarming | Free space <=10% / >10% and <=20% | +0.25 / +0.15 |
| Swarming | >=10 bee frames and >=6 brood frames | +0.10 |
| Swarming | Queen age >=2 years | +0.10 |
| Swarming | Alert / critical threshold | 0.45 / 0.75 |
| Supersedure | 1-4 central queen cells | +0.50 |
| Supersedure | Spotty / drone-only / absent / compact brood | +0.30 / +0.40 / +0.20 / -0.20 |
| Supersedure | Queen age >=2 years | +0.15 |
| Supersedure | Alert threshold | 0.55 |
| Stress/absconding | Raw Varroa count >=25 / 12-24 | +0.35 / +0.20 |
| Stress/absconding | Honey <1.5 kg / >=1.5 and <2.5 kg | +0.40 / +0.20 |
| Stress/absconding | Pest signs present | +0.25 |
| Stress/absconding | Raw Varroa count >=12 and honey <2.5 kg | +0.20 |
| Stress/absconding | Alert / critical threshold | 0.55 / 0.75 |

The source of the executable conditions is `DecisionSupportEngine.kt`. Scores are clipped to [0,1] and rounded to three decimal places before threshold comparison. Representation experiments retain this complete parameter set.

## Development use of Beewise

Antosiewicz confirmed using the 359 retained visits from [Beewise Colony Assessment 2025](https://doi.org/10.5281/zenodo.21907271) to tune the stress-related rule with the aim of reducing alerts from isolated observations. The development changes are preserved in [commit 20e02b887425db08d1efbccb9fba74879a75f393](https://github.com/MichalAntosiewicz/Apiary-Management-System/commit/20e02b887425db08d1efbccb9fba74879a75f393):

- Mite-count contributions became 0.35 (>=25) and 0.20 (>=12), from 0.50 (>=25) and 0.25 (>=10).
- Low-honey contributions became 0.40 (<1.5 kg) and 0.20 (<2.5 kg), from 0.35 (<1.5 kg) and 0.15 (<3 kg).
- The joint mite-count/low-store contribution of 0.20 was added.
- The alert threshold became 0.55, from 0.50. The critical threshold remained 0.75.

Each individual contribution is below the configured alert threshold. The Git diff establishes the parameter changes; the author's statement establishes their development-data basis. Beewise is therefore a development dataset for this rule. Its proxy agreement measures internal consistency of the adapter, scoring configuration and text-derived groupings. Cluster resampling accounts for repeated visits, while retaining that interpretation.

The 20 authored development scenarios contain ten supersedure, five swarming and five nominal labels. Their overall selected-class agreement is 19/20. `SUP-08` retains its supersedure label and yields no alert: 0.50 + 0.15 - 0.20 = 0.45 < 0.55. All twenty have stress scores of zero, mite counts 1-3, at least 4 kg of honey and no pest signs. They retain the absence of a stress alert; they contain no positive stress/absconding case. The complete inputs and outputs are in `outputs/author_scenarios.csv` and the JVM fixture.

## Measurement units and adapter assignments

`varroaMiteCount` accepts a raw integer count per inspection. The engine prescribes no sample size, collection method or normalization. Thresholds 12 and 25 apply to that count. The Beewise README separately defines `Varroa` (detected count) and `Mite_per_100` (mites per 100 adult bees). The sensitivity experiment treats rate substitution as an explicit change of input representation. Measurement interpretation requires the collection protocol associated with a particular deployment or dataset.

The 20-frame capacity and 2 kg-per-honey-frame conversion are adapter assignments. The factorial experiment varies both. Colony-strength methods provide measurement context, rather than these universal constants.

## Literature context

- Winston (1987), *The Biology of the Honey Bee*, provides the general biological account of colony reproduction and queen replacement used during development.
- [Delaplane et al. (2013)](https://doi.org/10.3896/IBRA.1.52.1.03) describes adult-bee, brood, food-resource and queen-cell assessment. Its [university-hosted full text](https://bees.caes.uga.edu/content/dam/caes-subsite/bee-program/images/research-archives/JARStandardmethodscolonystrength.1.03.pdf) discusses frame-specific conversions and measuring honey mass by weighing.
- [Dainat et al. (2020)](https://doi.org/10.1007/s13592-019-00728-2) describes the Liebefeld visual-assessment method with frame-specific bee counts and brood/food areas.
- [Lee et al. (2019)](https://doi.org/10.3390/insects10010012) examines context-dependent brood-pattern interpretation and queen exchange between colonies.
- [Human et al. (2013)](https://doi.org/10.3896/IBRA.1.52.4.10) describes research procedures including queen marking and age tracking, queen rearing and developmental stages.
- [Zhu et al. (2024)](https://doi.org/10.1038/s41597-024-03695-1) documents a longitudinal dataset with colony assessments and specified Varroa measurement procedures.

These sources support the biological concepts and measurement procedures named above. The exact central/marginal cell classifier, numerical cutoffs, additive weights and their combinations are documented as the author's configured rules. Fell et al. (1977), *The seasonal cycle of swarming in honeybees*, is cited in the article for seasonal context; it is not used as the source of the free-space cutoffs.
