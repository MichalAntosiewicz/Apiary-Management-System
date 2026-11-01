# Archived workflow verification

The archived run contains **111 passing JUnit cases**: 103 integration cases using actual HTTP and PostgreSQL, plus 8 service-level cases. `verification_summary.json` records the exact runtime versions and aggregate counts.

| Artifact | Meaning |
|---|---|
| `junit/TEST-*.xml` | Test identities, execution durations and outcomes; machine names, console streams and runtime properties are omitted |
| `workflow-matrix.csv` | 90 combinations of initial state, treatment condition and command; expected/observed states and HTTP results |
| `boundary-sequences.csv` | 18 HTTP steps covering boundaries, overlap, missed windows, rejection, rollback and concurrency |
| `postgres-version.txt` | Version returned by the native test database |
| `verification_summary.json` | Aggregate test counts, matrix agreement and runtime versions |
| `source_sha256.txt` | Source/build input hashes, using LF-normalized text for portability across operating systems |

The matrix has 0 state mismatches: 8 responses with HTTP 200, 50 with 201, 10 with 400 and 22 with 409. All 30 harvest combinations check the event, stored outcome and linked transition. Dedicated integration cases also check persistent audit rows after HTTP 409, complete rollback after an injected technical failure, row-lock serialization of a concurrent command and microsecond consistency of API/JSONB/SQL timestamps.

The fault-injection scenario deliberately produces HTTP 500. Its test passes only when no partial event, decision, transition or alert remains. The `decision` column in the boundary CSV contains the response's `status` field: the deliberate HTTP error therefore appears as `500`, while business outcomes appear as `APPROVED` or `REJECTED`. That error is not a persisted business decision.

Reproduce and archive from the repository root:

```sh
python research/analyze_beewise.py
sh ./gradlew test --no-daemon
python research/archive_verification.py
```

On Windows use `.\gradlew.bat test --no-daemon`. The archive script requires all 111 cases to pass; it rejects partial, failed or skipped runs. Full console output and HTML remain under the ignored `build/` directory. The committed XML export contains the successful outcomes needed to check counts without publishing machine-specific logs.

The test suite verifies the documented workflow and selected controlled interleavings. The service microbenchmark uses mock repositories and is not an HTTP/PostgreSQL throughput result. Fixture concordance measures agreement with explicit representation scenarios, not field diagnostic accuracy.

The separate fault-detection experiment keeps this successful baseline archive intact and executes isolated source variants with the same integration assertions. Its complete manifest, patches and results are under `research/mutation_results`; see [research/README.md](../research/README.md#workflow-fault-detection-experiment). Mutation-induced failures are expected experimental outcomes and are not mixed into the successful baseline totals.
