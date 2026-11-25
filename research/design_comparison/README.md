# Decision-policy comparison and structural transfer example

`WorkflowDesignComparisonTest` defines 33 comparisons and 10 transfer cases in two explicit CSV tables. Each row specifies its expected outcome before execution. The test uses an isolated native PostgreSQL database, the Spring HTTP server and a controlled clock. Generated files are archived only after all 43 cases pass.

## Reproduction

Use JDK 21 and the repository Gradle wrapper. From the repository root run

```sh
sh ./gradlew test --tests '*WorkflowDesignComparisonTest' --no-daemon
python research/summarize_design_comparison.py
```

On Windows use `.\gradlew.bat` for Gradle. Existing cached dependencies support adding `--offline`. The test writes `build/verification/workflow-design-comparison.csv` and `build/verification/batch-transfer.csv`. The summary script verifies the JUnit outcome, all case identities and the reported policy outcomes, then copies the results into this directory and records source hashes in `summary.json`.

## Comparison contracts

The production policy permits harvest when the lifecycle state allows it and no recorded treatment interval contains the command time. A treatment interval includes its start and excludes its final endpoint. The application obtains the hive lock and assigns the timestamp in its command transaction.

The reference policy permits harvest when the stored operational state is `PREPARATION` or `ACTIVE_HONEY_FLOW`. It blocks the two treatment phases as well as `WINTERING` and `DECOMMISSIONED`. This design requires a phase refresh to reflect an elapsed treatment endpoint. Each comparison explicitly enables or omits an inspection immediately before the harvest request. Both policies therefore have specified outcomes under their own contracts, including the conservative decision of the reference policy when its treatment phase has persisted beyond expiry.

Each row starts from a fresh fixture and sends the common treatment and optional inspection commands through production HTTP. The reference predicate then reads the stored phase. The real harvest request follows immediately, with the clock fixed and with exclusive use of the test database. The fixture permits one harvest decision, so the two policies share the complete preceding command history. The production path verifies the HTTP status, final state, decision row, originating event and audit transition at the common command timestamp. The reference baseline consists of the decision predicate evaluated on the pre-request snapshot.

The single course lasts one day with one additional day of withdrawal. The zero-withdrawal course lasts one day. In the overlap fixture a second one-day course with one additional day of withdrawal starts 12 hours after the first. Explicit cases cover exact start, administration end, restriction endpoints and adjacent seconds. `WINTERING` and `DECOMMISSIONED` retain their shared lifecycle restrictions.

The comparison isolates the choice of decision inputs and the refresh requirement. Both decision rules could be implemented within several application architectures. The recorded counts describe the specified cases, and the case table identifies every disagreement.

## Observed results

The isolated run passed all 43 JUnit cases. Each policy matched its 33 specified expectations. With a preceding inspection the policies agreed in all 16 cases. Without an inspection they agreed in 11 of 17 cases. The six differences were D09, D11, D15, D17, D23 and D25, at or after the final restriction endpoint for the single, zero-withdrawal and overlapping-course fixtures. In those cases the interval policy approved the harvest while the cached treatment phase continued to block it. The two shared lifecycle restrictions remained effective.

All ten mapped batch-release cases matched their specified outcomes. The production event, decision and transition records were verified after the HTTP response in all 43 cases. `summary.json` contains the aggregate counts and hashes of the exercised guard, command service, query, test and case tables. The two result CSV files retain the expected and observed outcome of every case.

## Structural transfer example

The second table defines a generic batch-release contract. An `AVAILABLE` batch may be released if the evaluation time belongs to none of its half-open hold intervals. A `SUSPENDED` or `RETIRED` batch remains blocked. These are stipulated software requirements for the example.

The test adapter maps the batch identifier to a hive identifier, a hold interval to a treatment row with administration end equal to restriction end and zero additional withdrawal days, and the lifecycle states to `PREPARATION`, `WINTERING` and `DECOMMISSIONED`. It submits the release request through the production harvest HTTP endpoint and compares the outcome with the independently listed release expectation. Thus the example executes the existing guard and persistence path for ten mapped cases.

The table includes an empty hold set, a hold start, a final endpoint and adjacent seconds, overlapping holds, a future hold and the two lifecycle vetoes. The example evaluates this structural mapping. Applying the pattern in an operational domain would additionally require that domain's rules for assigning, amending and ending holds, authorization, and acceptance testing with its users.
