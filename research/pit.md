# PIT mutation analysis

The opt-in Gradle init script pins Gradle PIT plugin 1.19.0, PIT 1.30.2 and the PIT JUnit 5 plugin 1.2.3. The application uses Kotlin 2.1.10, Java 21, Spring Boot 3.4.3 and Gradle 9.7.1.

From the repository root, run in PowerShell using a JDK 21 installation and the project's Gradle cache.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:GRADLE_USER_HOME = 'F:\Documents\Artykuły\2026\Antosiewicz\audit\gradle-home'
.\research\run_pit.ps1
```

The Windows launcher uses verified temporary ASCII junctions to the repository and Gradle cache. This accommodates PIT's Java launcher argument files when the project path contains Polish characters. The first execution downloads the pinned PIT dependencies. Subsequent executions can pass `-Offline` when those dependencies are cached. Native PostgreSQL requires permission to start local child processes and bind localhost. The test temporary directory defaults to the operating-system temporary directory plus `apiary-workflow-tests`. For a direct Gradle invocation in an ASCII workspace, use `gradlew -I research/pit.gradle pitest`; the test temporary directory can be overridden with `-PtestTempDir=C:\an-ascii-path`.

The fixed scope comprises `HiveStateMachineService`, `HiveOperationService`, `InspectionService` and their generated nested classes. PIT applies its `DEFAULTS` operators and selects covering methods from the existing `WorkflowIntegrationTest`. One mutation worker runs at a time. The timeout is four times the measured test duration plus 60 seconds to accommodate the Spring application and native PostgreSQL startup. A failed unmutated coverage run aborts analysis.

The init script supplies `-Dapiary.pit=true` to mutation JVMs. This fixture flag keeps the integration suite's PostgreSQL instance alive until JVM shutdown because PIT reuses the test class after discovery and between mutants. Ordinary Gradle test execution closes PostgreSQL in `@AfterAll`. The flag affects resource cleanup only. Database reset, requests, cases and assertions are shared with the regression suite.

Reports are written to `research/pit_results`. XML retains every mutant and its status, HTML provides source-linked inspection and CSV supports further analysis. The exported line-coverage report records the coverage used for test selection. Test execution also writes the integration suite's ordinary CSV output under `build/verification`, so archived regression results should be kept separately.

After completion, `python research/pit_summarize.py` exports `mutants_with_source.csv` and `summary.json`, including all status counts and hashes of the analysed source files.

The completed initial run is frozen in `research/pit_results_assessment`, including HTML, XML, line coverage, hashes, manually reviewed non-killed mutants and copies of analysed inputs. Full launcher logs are retained in the local QA archive. A later run can use a separate directory without overwriting that assessment.

```powershell
.\research\run_pit.ps1 -Offline -ReportDirectory research/pit_results_retest
python research/pit_summarize.py research/pit_results_retest
```

The report directory changes only the output location. The class targets, test class, operators, threads and timeout settings remain defined in `pit.gradle`. `pit_survivor_analysis.py` reproduces the fixed manual assessment of the initial XML and checks its hash before applying its row-specific explanations.

The completed assessment B is stored in the default `research/pit_results` directory. `python research/pit_compare.py` compares B with the archived A using stable mutant identities, coverage-test identities and canonical-LF production hashes. `python research/pit_final_analysis.py` produces the reviewed list of remaining mutants for B and aborts if a new non-killed status requires individual assessment. The two runs share 115 test identities. B adds metadata assertions, a redelivery step and explicitly matching body hive identifiers.

Hashes of exact file bytes are retained alongside canonical-LF hashes, which normalize CRLF to LF. This allows the production-source comparison to remain meaningful across Windows and Unix checkouts. The initial fixture hashes are derived from its archived inputs.

This scope exercises command services and event-listener guards through HTTP, Spring transaction proxies and PostgreSQL. Standard bytecode mutations cover expressions and calls inside these classes. Repository query strings and transaction or locking annotations require separate fault models. Kotlin-generated branches and possible equivalent mutants require manual survivor analysis. Results must retain `NO_COVERAGE`, timeout and runtime-error statuses alongside killed and surviving mutants.

## Official references checked on 17 November 2026

- Gradle plugin version and coordinates — https://plugins.gradle.org/plugin/info.solidsoft.pitest
- Gradle plugin configuration — https://github.com/szpak/gradle-pitest-plugin
- PIT 1.30.2 release — https://github.com/hcoles/pitest/releases/tag/pitest-parent-1.30.2
- JUnit 5 plugin 1.2.3 and version compatibility — https://github.com/pitest/pitest-junit5-plugin
- PIT mutation and timeout options — https://pitest.org/quickstart/commandline/
