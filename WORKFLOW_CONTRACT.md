# Workflow contract and verification

The application implements synchronous event processing with a relational event and decision audit. Its supported HTTP commands record an inspection, record a treatment, and request a harvest decision. The verified workflow consists of these three commands. Biological intervention listeners provide a separate prototype subset of the colony model.

## Command and time semantics

Each supported write command obtains a PostgreSQL write lock on its hive row inside its Spring service transaction. It then assigns a timestamp using the application `Clock` (UTC), truncated to PostgreSQL microsecond precision. Clients cannot supply the decision timestamp. Event consumers use `occurredAt` consistently for treatment-window evaluation and transition timestamps. The diagnostic engine uses the UTC year of the inspection event for its queen-age calculation; it still reads the currently stored queen and is not a historical replay engine.

Treatment administration occupies `[startDate, treatmentEndDate)` and the entered post-administration restriction occupies `[treatmentEndDate, withdrawalEndDate)`. A treatment blocks harvest throughout `[startDate, withdrawalEndDate)`. At the exact withdrawal endpoint it no longer blocks harvest. A zero-day withdrawal interval is permitted; a treatment duration must be positive. Medicine names and durations are user-entered records. The software does not determine the legally or clinically correct period for a product.

The operational state is reconciled on the next inspection. Any ongoing administration takes precedence over a remaining withdrawal interval from another treatment. If no active treatment remains, both `TREATMENT_IN_PROGRESS` and `WITHDRAWAL` return directly to `PREPARATION`. There is no autonomous timer changing stored states between commands. Harvest independently checks treatment records at its command timestamp, including when no intervening inspection occurred.

## Business rejection versus technical failure

A harvest command is accepted only when the hive is neither `WINTERING` nor `DECOMMISSIONED`, and no recorded treatment restriction is active. The event, a `harvest_decisions` row, and an audit transition with the same `event_id` are committed together. A treatment-based rejection also records a linked sanitary alert. Rejections preserve the existing hive state. The controller produces HTTP 409 only after this service transaction commits. Approved decisions return HTTP 200, and the hive enters `ACTIVE_HONEY_FLOW`.

An unexpected failure in event processing aborts the transaction: event, outcome, alert, transition and state update roll back together. Input-validation errors produce HTTP 400 before any event is accepted; they are outside the accepted-command audit. A commit failure cannot yield a successful business response. A client retry is a new command and receives a new event ID; HTTP idempotency keys are not implemented. The harvest listener avoids processing an already persisted event ID twice.

`DECOMMISSIONED` blocks inspection and treatment commands. `WINTERING` blocks harvest but can be inspected and treated. Treatment-related states take precedence during inspection; the application does not preserve a separate seasonal dimension when entering treatment.

## State reachability and initialization

The enum vocabulary is broader than the reachable transitions implemented by the three HTTP commands. New `Hive` objects default to `PREPARATION` and `NOMINAL`. The application has no public command for creating a hive, entering wintering, recording decommissioning, or confirming that a colony has swarmed. Initial states are supplied by database fixtures or another persistence/import path; the integration matrix explicitly initializes all six operational states before exercising commands.

| State | Implemented entry and behavior |
|---|---|
| `PREPARATION` | Default operational value; inspection returns an expired treatment/withdrawal state here. |
| `ACTIVE_HONEY_FLOW` | An approved harvest decision enters this state. |
| `TREATMENT_IN_PROGRESS` | Treatment recording enters this state; inspection selects it while any administration interval remains active. |
| `WITHDRAWAL` | Inspection enters this state if a restriction remains and no administration interval is active. |
| `WINTERING` | No entry command or event is implemented. An initialized wintering hive rejects harvest. Inspection preserves it when no active treatment exists; an active treatment or treatment command supersedes it. |
| `DECOMMISSIONED` | No entry command or event is implemented. Once initialized, all three commands are rejected and preserve the state. |
| `NOMINAL` | Default biological value; recovery guards and the two prototype intervention listeners can return to it. |
| `CONGESTED` | Inspection enters it from `NOMINAL` when free comb space is at most 10%, unless a higher-priority biological guard applies. |
| `SWARM_PREPARATION` | Inspection enters it when marginal queen cells are present. |
| `SUPERSEDURE_ACTIVE` | Inspection enters it when one to four central queen cells are present. |
| `ABSCONDING_RISK` | Inspection enters it under the implemented parasite/store/pest guard when earlier queen-cell guards do not apply. This is a heuristic state label. |
| `SWARMED` | Reserved enum value with no dedicated entry event or transition. It is not reachable from default states through the supported commands. If supplied externally, an inspection may overwrite it through the general queen-cell or stress guards; otherwise it persists. |

`SwarmInterventionAppliedEvent` and `QueenReplacedEvent` have listeners but no HTTP-producing commands in this artifact. Their listeners reset `SWARM_PREPARATION` or `SUPERSEDURE_ACTIVE`, respectively, to `NOMINAL` under their explicit conditions. They do not create a `SWARMED` state. The tests support the specified operational command contract; the six biological enum values should not be presented as a fully exercised or complete colony lifecycle.

## Independent test scenarios

`WorkflowIntegrationTest` starts native PostgreSQL on an automatically selected localhost port and the Spring HTTP server on another random port. It runs actual Flyway migrations, Hibernate persistence, transaction proxies, request validation and JSON handling. Its matrix covers:

- Six initial operational states.
- Five treatment conditions: none, administration, withdrawal, expired, overlapping administration and withdrawal.
- Three commands: harvest, inspection, treatment.

The resulting 90 cases check HTTP status, persisted final state and, for harvest, the linked event/decision/transition plus alert cardinality. The complete integration suite contains 103 JUnit cases. Additional scenarios check exact boundaries, missed inspection windows, zero withdrawal, overlapping treatment sequences, durable HTTP 409 records, invalid inputs, injected post-decision failure, row-lock serialization against a concurrent HTTP request, and microsecond agreement between API, JSONB events and SQL timestamps. Test bodies do not run in a test rollback transaction; database assertions occur after HTTP completion.

The concurrency test explicitly observes the waiting PostgreSQL transaction in `pg_stat_activity`, commits treatment while holding the common hive lock, and checks that the waiting harvest subsequently rejects. This verifies that controlled interleaving, not every possible production workload.

Results are generated in `build/verification/workflow-matrix.csv`, `boundary-sequences.csv`, and `postgres-version.txt`; JUnit XML and HTML live under `build/test-results/test` and `build/reports/tests/test`. In-memory service tests and the service microbenchmark do not measure database or HTTP throughput. The research fixture test checks concordance with explicit data-adapter scenarios, not biological diagnosis accuracy.

## Running

Use JDK 21 and the committed Gradle wrapper:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
.\gradlew.bat test --no-daemon
```

The first run downloads public dependencies and PostgreSQL test binaries. Docker and a preinstalled PostgreSQL service are not required. Native PostgreSQL uses the OS temporary directory and stops after the integration class. On Windows, use an ASCII temporary path if the OS temporary directory includes characters unsupported by `initdb`, for example `-PtestTempDir=C:\Temp\apiary-tests`. The integration-test HTTP server is a temporary test process; no deployment is performed.

The original V1/V2 migrations are preserved. V2 contains upstream demonstration data, including incomplete historical event examples; the integration suite deletes those fixtures from its isolated database before each scenario. V3 adds decision storage, event links and database constraints. V2 must not be interpreted as an event-replay dataset or field evidence.

Global authentication, authorization, a regulated medicine catalog, a durable distributed event broker and full historical reconstruction are outside this verified workflow. Do not expose the development application as a production multi-user service.
