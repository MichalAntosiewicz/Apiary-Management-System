# Assessment B and comparison with assessment A

PIT completed successfully on 17 November 2026. Assessment B uses the same production source bodies, 131 generated mutant identities, operator configuration and 115 integration-test identities as assessment A. The test changes add HTTP/SQL metadata assertions, an inspection event-to-transition join, an event-redelivery step and an explicitly matching body hive identifier. The three service targets and their biological prototype bodies remain in the mutation scope.

## Complete results

| Status or measure | Assessment A | Assessment B |
|---|---:|---:|
| KILLED | 39 | 49 |
| SURVIVED | 52 | 42 |
| NO_COVERAGE | 40 | 40 |
| TIMED_OUT | 0 | 0 |
| NON_VIABLE | 0 | 0 |
| MEMORY_ERROR | 0 | 0 |
| RUN_ERROR | 0 | 0 |
| NOT_STARTED | 0 | 0 |
| STARTED | 0 | 0 |
| EQUIVALENT | 0 | 0 |
| Total mutants | 131 | 131 |
| Killed/all | 29.77% | 37.40% |
| Killed/(killed + survived) | 42.86% | 53.85% |
| Line coverage of targeted classes | 207/260 | 207/260 |

All mutants are retained in these denominators. Ten survivors became kills. Every previous kill remained killed, and all 40 uncovered mutants retained their status. PIT reports 1833 test executions and a total analysis time of 7 minutes 3 seconds for B, including 41 seconds of coverage collection. The Gradle command completed in 7 minutes 30 seconds.

The tools were Gradle PIT plugin 1.19.0, PIT 1.30.2 and JUnit 5 plugin 1.2.3, with Kotlin 2.1.10, JetBrains JDK 21.0.8, Spring Boot 3.4.3, Gradle 9.7.1 and native PostgreSQL 14.22. The run used `DEFAULTS`, PIT's default filters, one worker, a timeout factor of four and a 60-second timeout constant. The built-in Kotlin filter was active. Full launcher logs are retained in the local QA archive.

## Detected workflow gaps

The ten status changes cover one body-ID equality branch, the inspection-triggered operational audit call, the duplicate-decision shortcut, the active-withdrawal reason code, two rejection-message branches, the transition outcome prefix, two `blockedUntil` branches and the maximum-withdrawal-endpoint comparison. The comparison CSV identifies every changed mutant and its killing test.

These additions establish concrete observations that were absent from assessment A. The replay step exercises repeated delivery of an existing event. The matching body identifier exercises an additional valid representation of a treatment request. The test method and parameterized-case identities remain the same, as verified from both exported coverage reports.

## Scope by implementation body

| Implementation body | Killed | Survived | No coverage | Total |
|---|---:|---:|---:|---:|
| Harvest, treatment and operational-reconciliation listener bodies | 36 | 7 | 1 | 44 |
| Transactional command-service bodies | 11 | 22 | 8 | 41 |
| Inspection listener lookup, operational guard and operational delegation | 2 | 2 | 0 | 4 |
| Biological rule body within the inspection listener | 0 | 11 | 19 | 30 |
| Queen-replacement and swarm-intervention listener bodies | 0 | 0 | 12 | 12 |
| Total | 49 | 42 | 40 | 131 |

This partition describes the fixed class scope. The biological inspection body comprises source lines 36–75 of `onInspectionRecorded`. Its operational checks and delegation are reported separately. Kotlin SMAP maps generated lines 226–239 to `maxByOrNull` at source line 115 and generated lines 240–242 to `any` at source line 189.

The 42 remaining survivors comprise 17 Kotlin non-null checks, two collection fast-path checks, one event-name fallback, four repeated decommissioned-state guards, four request-validation branches or calls, two listener lookup branches, one equal-endpoint treatment-selection boundary and 11 biological prototype conditionals. The 40 uncovered mutants comprise 31 biological inspection/intervention instructions, four missing-hive exception suppliers, one missing-decision exception supplier and four validation-error formatting instructions.

Repeated checks can preserve an HTTP result because the other validation or listener layer applies the same restriction. The equal-endpoint comparison can select another treatment sharing the same latest withdrawal timestamp. Assessment B observes distinct endpoints and their explanations. The biological fixtures retain healthy nominal inputs. Every remaining mutant has an individual explanation in `non_killed_analysis.csv`, and every raw status remains visible.

## Reproduction and provenance

From the repository root, set `JAVA_HOME` to JDK 21 and `GRADLE_USER_HOME` to the desired cache, then run the following PowerShell commands. The offline option requires the pinned dependencies to be cached.

```powershell
.\research\run_pit.ps1 -Offline
python research/pit_summarize.py
python research/pit_compare.py
python research/pit_final_analysis.py
```

The Windows launcher uses verified ASCII junctions for PIT's Java argument files. The fixture flag `apiary.pit=true` keeps the embedded database alive until JVM shutdown while PIT reuses the Spring test context. The unmutated coverage run passed all 115 cases before mutation execution.

`summary.json` stores exact-byte and canonical-LF hashes. Canonical hashes replace CRLF with LF and use POSIX relative paths. `assessment_comparison.json` records identical production hashes, mutant identities and coverage-test identities, together with the distinct fixture hashes and XML hashes. `assessment_comparison.csv` records the outcome pair for every mutant. `research/pit_results_assessment` preserves the complete initial report and its input snapshots.

The mutation experiment evaluates the assertions against the configured bytecode operators and three selected service classes. Repository query strings, locking annotations and transaction boundaries are represented separately in the manually specified fault experiment.
