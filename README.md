# Apiary Management System: research artifact

This repository accompanies **Event-Driven State Modelling and Workflow Verification for Apiary Management**. It contains a Kotlin/Spring implementation, executable workflow specifications, PostgreSQL/HTTP integration tests, and reproducible experiments on the representation of inspection data.

## Reproduce the results

Requirements: **JDK 21** and **Python 3.10 or later**. Python uses only its standard library. The committed Gradle wrapper downloads the JVM dependencies and native PostgreSQL test binaries on first execution. The tests start their own database and HTTP server; Docker and an existing PostgreSQL service are not required.

From the repository root:

```sh
python research/analyze_beewise.py
sh ./gradlew test --no-daemon
python research/archive_verification.py
```

On Windows, use `.\gradlew.bat test --no-daemon` for the second command. Set `JAVA_HOME` to a JDK 21 installation when another Java version is selected by the shell. Subsequent test runs can use `--offline` after dependencies have been downloaded. If native Windows `initdb` cannot handle characters in the temporary-directory path, supply an ASCII path with `-PtestTempDir=C:\Temp\apiary-tests`.

The archived run was verified on Windows x86_64. Native PostgreSQL requires compatible operating-system libraries; Linux runs require a non-root user, and Windows may require the Visual C++ runtime supported by the native binaries.

The archived run contains **111 passing JUnit cases**: **103 integration cases** using actual HTTP and native PostgreSQL 14.22, and **8 service-level tests**. The workflow matrix covers 90 combinations of six operational states, five treatment conditions and three commands. Additional integration cases cover validation, durable rejection records, rollback, exact time boundaries, overlapping treatments, locking, and timestamp precision. Two service tests compare **6692 generated input configurations** with the Kotlin rule engine.

## Artifact contents

| Path | Purpose |
|---|---|
| `src/main` | Application, transactional command services, event consumers and Flyway migrations |
| `src/test` | Workflow tests, service tests and generated cross-language fixtures |
| [WORKFLOW_CONTRACT.md](WORKFLOW_CONTRACT.md) | Command contract, event-time semantics and verified scope |
| [research/README.md](research/README.md) | Inspection-data experiments, source provenance and output definitions |
| `research/upstream_41521e6.zip` | Immutable source snapshot used to reproduce the input adapter |
| `research/outputs` | Per-case results, representation comparisons and generated tables |
| [verification/README.md](verification/README.md) | Interpretation and reproduction of the archived verification results |
| `verification/junit` | Compact JUnit results without machine names or raw console output |
| `verification/verification_summary.json` | Test totals, workflow coverage and runtime versions |
| `verification/source_sha256.txt` | SHA-256 hashes of application, test and build inputs |

The analysis reproduces a fixed rule engine and measures how explicit input representations change its outputs. Proxy-label agreement and author-defined scenarios are distinguished from independent field diagnosis. The workflow experiments verify the supported command contract; they do not establish biological prediction accuracy or production throughput.

## Run the application demonstration

The application demonstration uses PostgreSQL 16 from `compose.yaml`:

```sh
docker compose up -d
sh ./gradlew bootRun
```

Use `.\gradlew.bat bootRun` on Windows. The API exposes inspection recording, treatment recording and harvest decisions. `requests.http` contains sample calls using the demonstration fixtures. The Compose credentials are development examples. Authentication, authorization and a regulated medicine catalog are outside the research artifact's verified scope.

## Provenance

The fixed inspection-data adapter is preserved from source commit `41521e6`. Its source files and input hashes are included in `research/upstream` and `research/outputs/analysis_summary.json`. The source dataset is [Beewise Colony Assessment 2025](https://doi.org/10.5281/zenodo.21907271). The repository includes its original CSV and preserves the recorded observations.
