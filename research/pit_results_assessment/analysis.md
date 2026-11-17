# Assessment A — automatic mutation analysis

The run completed successfully on 17 November 2026 using PIT 1.30.2, Gradle PIT plugin 1.19.0 and JUnit 5 plugin 1.2.3. The environment was Kotlin 2.1.10, JetBrains JDK 21.0.8, Spring Boot 3.4.3, Gradle 9.7.1 and native PostgreSQL 14.22. The configured `DEFAULTS` operator group, default PIT filters and one mutation worker were used. PIT's built-in Kotlin filter was enabled. The optional Arcmutate Kotlin and Spring plugins were absent.

## Results

| Status | Count |
|---|---:|
| KILLED | 39 |
| SURVIVED | 52 |
| NO_COVERAGE | 40 |
| TIMED_OUT | 0 |
| NON_VIABLE | 0 |
| MEMORY_ERROR | 0 |
| RUN_ERROR | 0 |
| NOT_STARTED | 0 |
| STARTED | 0 |
| EQUIVALENT | 0 |
| Total | 131 |

The raw killed/all score is **39/131 = 29.77%**. The killed/(killed + survived) ratio is **39/91 = 42.86%**. PIT reports line coverage of **207/260 = 79.62%**, 115 distinct integration tests discovered and executed during unmutated coverage collection, and 2084 test executions during its reported analysis. Total PIT time was 8 minutes 1 second, including 38 seconds for coverage collection. Gradle completed in 8 minutes 26 seconds.

All 131 generated mutants contribute to the raw score. The manual review retains the reported statuses of Kotlin checks, repeated guards and biological prototype branches. The report contains no post hoc equivalent-mutant exclusions.

## Scope by implementation body

| Implementation body | Killed | Survived | No coverage | Total |
|---|---:|---:|---:|---:|
| Harvest, treatment and operational-reconciliation listener bodies | 27 | 16 | 1 | 44 |
| Transactional command-service bodies | 10 | 23 | 8 | 41 |
| Inspection listener lookup, operational guard and operational delegation | 2 | 2 | 0 | 4 |
| Biological rule body within the inspection listener | 0 | 11 | 19 | 30 |
| Queen-replacement and swarm-intervention listener bodies | 0 | 0 | 12 | 12 |
| Total | 39 | 52 | 40 | 131 |

This descriptive partition maps every mutant to its implementation body. The three class targets were fixed before the run. The biological inspection body is source lines 36–75 of `onInspectionRecorded`. Its hive lookup, decommissioned-state guard and operational reconciliation call are recorded separately. Kotlin generated lines 226–239 map through SMAP to `maxByOrNull` at source line 115, and generated lines 240–242 map to `any` at source line 189.

## Observable workflow gaps

- Removing the inspection-triggered operational `recordTransition` call survived. The suite asserts the resulting operational state but does not require its linked inspection transition row.
- Changing the active-treatment rejection reason survived. Existing assertions distinguish approval and wintering rejection, with incomplete checks of `ACTIVE_WITHDRAWAL`.
- Mutations of `blockedUntil` and active-treatment selection survived. Rejection remains observable when any active restriction exists, while the selected latest expiry and treatment explanation require additional assertions.
- Mutations of rejection messages and the transition reason's approval prefix survived. The persisted transition and decision links remain present under these mutations.
- Removing the existing-decision shortcut survived because HTTP commands create fresh event identifiers and this suite does not redeliver an event.
- A branch for an explicitly supplied body hive ID matching the URL survived. The suite covers omitted and mismatched body IDs.

The suite killed mutations of core approval selection, operational state updates, treatment interval addition, event publication, treatment/harvest persistence calls and selected transition-audit calls. The remaining findings identify narrower test-observation gaps within the same transactional workflow.

## Other surviving or uncovered instructions

Repeated validation and decommissioned-state checks can preserve the tested HTTP outcome because another layer applies the same restriction. The HTTP controller validates request DTOs, and both command services and synchronous listeners guard decommissioned hives. Kotlin non-null checks operate on non-null values along the exercised paths. Two surviving `any` mutations affect collection fast-path checks. These are contextual explanations of survival under the executed suite.

The operational fixture uses healthy inspections with no queen cells, nominal biological state and inputs away from biological thresholds. Biological decision and recovery branches consequently remain either undistinguished or uncovered. Queen-replacement and swarm-intervention event listeners are outside the three-command suite. Missing-hive exception suppliers and local validation-error formatting paths are also uncovered. No biological equivalence claim follows from these results.

`non_killed_analysis.csv` supplies an individual reason for each of the 52 surviving and 40 uncovered mutants. Each row preserves its full class, method, mutator, bytecode instruction index, source location and raw status. `mutants_with_source.csv` includes every mutant, including kills and their killing tests. `summary.json` records source, fixture, configuration and XML hashes.

## Execution checks

The initial Unicode-path attempt failed before test discovery. ASCII junctions to the repository and Gradle cache resolved the Java launcher classpath problem on Windows. A second attempt exposed premature PostgreSQL shutdown after JUnit discovery. That attempt was stopped before producing mutation results.

The completed run passes `-Dapiary.pit=true`. This fixture-only flag defers PostgreSQL cleanup to JVM shutdown so that PIT can reuse the Spring context and test class. The ordinary `@AfterAll` reporting remains active. The flag preserves test inputs and assertions. Unmutated coverage collection passed all 115 cases, and subsequent mutant executions produced both killed and surviving results without timeout or runtime-error statuses.

Standard PIT operators mutate bytecode expressions, control flow and calls in the three service classes. The separately designed mutation experiment covers additional failures such as locking, transaction boundaries and repository predicates. The two mutation analyses have distinct fault models.

## Official tool references

- [Gradle PIT plugin](https://plugins.gradle.org/plugin/info.solidsoft.pitest)
- [PIT 1.30.2 release](https://github.com/hcoles/pitest/releases/tag/pitest-parent-1.30.2)
- [JUnit 5 plugin compatibility](https://github.com/pitest/pitest-junit5-plugin)
- [PIT configuration and score definitions](https://pitest.org/quickstart/commandline/)
