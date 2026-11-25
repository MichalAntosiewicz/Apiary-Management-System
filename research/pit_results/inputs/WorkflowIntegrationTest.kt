package io.github.Michal_Antosiewicz.apiary_management_system

import com.fasterxml.jackson.databind.JsonNode
import io.github.Michal_Antosiewicz.apiary_management_system.domain.OperationalState
import io.github.Michal_Antosiewicz.apiary_management_system.dto.ApplyTreatmentRequest
import io.github.Michal_Antosiewicz.apiary_management_system.event.HoneyHarvestAttemptedEvent
import io.github.Michal_Antosiewicz.apiary_management_system.repository.HiveRepository
import io.github.Michal_Antosiewicz.apiary_management_system.service.HiveOperationService
import io.github.Michal_Antosiewicz.apiary_management_system.service.HiveStateMachineService
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.Arguments
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.sql.Timestamp
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.stream.Stream

/** Actual random-port HTTP, Spring proxies, Flyway, Hibernate and native PostgreSQL.
 * Assertions use fresh database reads after HTTP completion, never a test transaction.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.docker.compose.enabled=false", "spring.jpa.open-in-view=false",
        "spring.jpa.show-sql=false", "spring.datasource.hikari.maximum-pool-size=8"])
@Import(WorkflowIntegrationTest.TestBeans::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkflowIntegrationTest {
    companion object {
        private val pitMode = java.lang.Boolean.getBoolean("apiary.pit")
        private val pg = EmbeddedPostgres.builder().setPort(0)
            .setServerConfig("listen_addresses", "127.0.0.1").start().also { database ->
                if (pitMode) Runtime.getRuntime().addShutdownHook(Thread { database.close() })
            }
        private val origin = Instant.parse("2026-01-01T12:00:00Z")
        @JvmStatic @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { pg.getJdbcUrl("postgres", "postgres") }
            registry.add("spring.datasource.username") { "postgres" }
            registry.add("spring.datasource.password") { "postgres" }
        }
        @JvmStatic fun matrix(): Stream<Arguments> = OperationalState.entries.flatMap { state ->
            listOf("NONE", "ADMINISTRATION", "WITHDRAWAL", "EXPIRED", "OVERLAP").flatMap { condition ->
                listOf("HARVEST", "INSPECTION", "TREATMENT").map { command -> Arguments.of(state, condition, command) }
            }
        }.stream()
        @JvmStatic fun winteringSequences(): Stream<Arguments> = listOf(1, 2).flatMap { courses ->
            listOf(false, true).flatMap { inspections ->
                listOf(-1L, 0L, 1L).map { secondsFromLastExpiry -> Arguments.of(courses, inspections, secondsFromLastExpiry) }
            }
        }.stream()
        @JvmStatic fun invalidRequests(): Stream<Arguments> = listOf(
            Arguments.of("treatments", mapOf("medicineName" to "TEST", "activeSubstance" to "TEST", "durationDays" to -1, "withdrawalDays" to 1)),
            Arguments.of("treatments", mapOf("medicineName" to "TEST", "activeSubstance" to "TEST", "durationDays" to 0, "withdrawalDays" to 1)),
            Arguments.of("treatments", mapOf("medicineName" to "TEST", "activeSubstance" to "TEST", "durationDays" to 1, "withdrawalDays" to -1)),
            Arguments.of("treatments", mapOf("medicineName" to " ", "activeSubstance" to "TEST", "durationDays" to 1, "withdrawalDays" to 1)),
            Arguments.of("harvest", mapOf("targetSuperCount" to 0)),
            Arguments.of("treatments", mapOf("hiveId" to UUID.randomUUID(), "medicineName" to "TEST", "activeSubstance" to "TEST", "durationDays" to 1, "withdrawalDays" to 1))
        ).stream()
    }
    class MutableClock : Clock() {
        @Volatile var time: Instant = origin
        override fun instant(): Instant = time
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(time, zone)
    }
    class FailureInjector {
        val failNext = AtomicBoolean(false)
        @EventListener @Order(Int.MAX_VALUE)
        fun afterDecision(event: HoneyHarvestAttemptedEvent) {
            if (failNext.getAndSet(false)) error("Injected failure after harvest decision: ${event.eventId}")
        }
    }
    @TestConfiguration
    class TestBeans {
        @Bean @Primary fun testClock() = MutableClock()
        @Bean fun failureInjector() = FailureInjector()
    }
    @Autowired lateinit var http: TestRestTemplate
    @Autowired lateinit var db: JdbcTemplate
    @Autowired lateinit var clock: MutableClock
    @Autowired lateinit var failures: FailureInjector
    @Autowired lateinit var transactions: PlatformTransactionManager
    @Autowired lateinit var hives: HiveRepository
    @Autowired lateinit var operations: HiveOperationService
    @Autowired lateinit var stateMachine: HiveStateMachineService
    private val matrixRows = mutableListOf("initial_state,treatment_condition,command,http_status,expected_state,actual_state,decision")
    private val boundaryRows = mutableListOf("scenario,time,command,http_status,actual_state,decision")

    @BeforeEach
    fun cleanDatabase() {
        // This database was created exclusively by EmbeddedPostgres above.
        db.execute("TRUNCATE apiaries CASCADE")
        clock.time = origin
        failures.failNext.set(false)
    }
    @AfterAll
    fun reportAndStop() {
        val dir = Path.of("build", "verification")
        Files.createDirectories(dir)
        Files.write(dir.resolve("workflow-matrix.csv"), matrixRows)
        Files.write(dir.resolve("boundary-sequences.csv"), boundaryRows)
        Files.writeString(dir.resolve("postgres-version.txt"), db.queryForObject("SELECT version()", String::class.java)!!)
        if (!pitMode) pg.close()
    }
    private fun newHive(state: OperationalState = OperationalState.PREPARATION): UUID {
        val apiary = UUID.randomUUID(); val hive = UUID.randomUUID()
        db.update("INSERT INTO apiaries(id,name,latitude,longitude) VALUES (?,?,0,0)", apiary, "Integration fixture")
        db.update("INSERT INTO hives(id,apiary_id,hive_number,hive_type,operational_state) VALUES (?,?,?,?,?::operational_state_enum)",
            hive, apiary, "TEST", "TEST", state.name)
        return hive
    }
    private fun treatment(hive: UUID, start: Instant, end: Instant, withdrawal: Instant) {
        db.update("INSERT INTO treatments(id,hive_id,medicine_name,active_substance,start_date,treatment_end_date,withdrawal_days,withdrawal_end_date) VALUES (?,?,?,?,?,?,?,?)",
            UUID.randomUUID(), hive, "TEST MEDICINE", "TEST SUBSTANCE", Timestamp.from(start), Timestamp.from(end),
            java.time.Duration.between(end, withdrawal).toDays().toInt(), Timestamp.from(withdrawal))
    }
    private fun condition(hive: UUID, type: String) {
        when (type) {
            "ADMINISTRATION" -> treatment(hive, origin.minusSeconds(86400), origin.plusSeconds(86400), origin.plusSeconds(172800))
            "WITHDRAWAL" -> treatment(hive, origin.minusSeconds(172800), origin.minusSeconds(86400), origin.plusSeconds(86400))
            "EXPIRED" -> treatment(hive, origin.minusSeconds(259200), origin.minusSeconds(172800), origin.minusSeconds(86400))
            "OVERLAP" -> { condition(hive, "WITHDRAWAL"); condition(hive, "ADMINISTRATION") }
        }
    }
    private fun post(hive: UUID, command: String): ResponseEntity<JsonNode> = when (command) {
        "HARVEST" -> http.postForEntity("/api/v1/hives/$hive/harvest", mapOf("targetSuperCount" to 1), JsonNode::class.java)
        "TREATMENT" -> http.postForEntity("/api/v1/hives/$hive/treatments", mapOf("hiveId" to hive, "medicineName" to "TEST MEDICINE",
            "activeSubstance" to "TEST", "durationDays" to 1, "withdrawalDays" to 1), JsonNode::class.java)
        else -> http.postForEntity("/api/v1/inspections", mapOf("hiveId" to hive, "framesOfBees" to 8,
            "framesOfBrood" to 4, "honeyStoresKg" to 8, "freeCombSpacePercent" to 30,
            "queenSeen" to true, "broodPattern" to "SOLID_COMPACT"), JsonNode::class.java)
    }
    private fun state(hive: UUID): String = db.queryForObject("SELECT operational_state::text FROM hives WHERE id=?", String::class.java, hive)!!
    private fun count(table: String): Int = db.queryForObject("SELECT count(*) FROM $table", Int::class.java)!!
    private fun logBoundary(name: String, hive: UUID, command: String, response: ResponseEntity<JsonNode>) {
        boundaryRows.add("$name,${clock.time},$command,${response.statusCode.value()},${state(hive)},${response.body?.path("status")?.asText("")}")
    }

    @ParameterizedTest(name = "{0}/{1}/{2}") @MethodSource("matrix")
    fun operationalConformance(initial: OperationalState, treatmentCondition: String, command: String) {
        val hive = newHive(initial); condition(hive, treatmentCondition)
        val active = treatmentCondition in setOf("ADMINISTRATION", "WITHDRAWAL", "OVERLAP")
        val blocked = initial in setOf(OperationalState.DECOMMISSIONED, OperationalState.WINTERING)
        // Independently stated contract: terminal commands are rejected; active administration outranks withdrawal.
        val expectedStatus = when {
            command == "HARVEST" -> if (blocked || active) 409 else 200
            initial == OperationalState.DECOMMISSIONED -> 400
            else -> 201
        }
        val expectedState = when {
            command == "HARVEST" -> if (blocked || active) initial else OperationalState.ACTIVE_HONEY_FLOW
            initial == OperationalState.DECOMMISSIONED || initial == OperationalState.WINTERING -> initial
            command == "TREATMENT" -> OperationalState.TREATMENT_IN_PROGRESS
            treatmentCondition in setOf("ADMINISTRATION", "OVERLAP") -> OperationalState.TREATMENT_IN_PROGRESS
            treatmentCondition == "WITHDRAWAL" -> OperationalState.WITHDRAWAL
            initial in setOf(OperationalState.TREATMENT_IN_PROGRESS, OperationalState.WITHDRAWAL) -> OperationalState.PREPARATION
            else -> initial
        }
        val response = post(hive, command)
        val actual = state(hive)
        matrixRows.add("$initial,$treatmentCondition,$command,${response.statusCode.value()},$expectedState,$actual,${response.body?.path("status")?.asText("")}")
        assertEquals(expectedStatus, response.statusCode.value(), response.body.toString())
        assertEquals(expectedState.name, actual)
        if (command == "HARVEST") {
            val id = UUID.fromString(response.body!!.path("eventId").asText())
            assertEquals(1, count("domain_events")); assertEquals(1, count("harvest_decisions"))
            assertEquals(1, db.queryForObject("SELECT count(*) FROM hive_state_transitions WHERE event_id=?", Int::class.java, id))
            assertEquals(expectedStatus == 200, db.queryForObject("SELECT approved FROM harvest_decisions WHERE event_id=?", Boolean::class.java, id))
            assertEquals(if (active && !blocked) 1 else 0, count("decision_alerts"))
            val expectedReason = when {
                blocked -> "OPERATIONAL_STATE_BLOCKED"
                active -> "ACTIVE_WITHDRAWAL"
                else -> "SANITARY_GUARD_CLEARED"
            }
            val expectedBlockedUntil = when {
                blocked || !active -> null
                treatmentCondition == "WITHDRAWAL" -> origin.plusSeconds(86400)
                else -> origin.plusSeconds(172800)
            }
            val body = response.body!!
            assertEquals(expectedReason, body.path("reasonCode").asText())
            assertEquals(expectedReason, db.queryForObject("SELECT reason_code FROM harvest_decisions WHERE event_id=?", String::class.java, id))
            val blockedUntilNode = body.path("blockedUntil")
            val actualBlockedUntil = if (blockedUntilNode.isNull || blockedUntilNode.isMissingNode) null else Instant.parse(blockedUntilNode.asText())
            assertEquals(expectedBlockedUntil, actualBlockedUntil)
            assertEquals(expectedBlockedUntil, db.queryForObject("SELECT blocked_until FROM harvest_decisions WHERE event_id=?", Timestamp::class.java, id)?.toInstant())
            val message = body.path("message").asText()
            assertTrue(message.isNotBlank(), "Every decision must explain its outcome")
            when {
                blocked -> {
                    assertTrue(message.contains("Harvest rejected"), message)
                    assertTrue(message.contains(initial.name), message)
                }
                active -> {
                    assertTrue(message.contains("Harvest rejected"), message)
                    assertTrue(message.contains("TEST MEDICINE"), message)
                    assertTrue(message.contains(expectedBlockedUntil.toString()), message)
                }
                else -> assertTrue(message.contains("harvest permitted"), message)
            }
            assertEquals(message, db.queryForObject("SELECT message FROM harvest_decisions WHERE event_id=?", String::class.java, id))
            val auditReason = db.queryForObject("SELECT reason FROM hive_state_transitions WHERE event_id=?", String::class.java, id)!!
            val expectedOutcome = if (expectedStatus == 200) "APPROVED" else "REJECTED"
            assertTrue(auditReason.startsWith("$expectedOutcome: $expectedReason;"), auditReason)
            assertTrue(auditReason.endsWith(message), auditReason)
        } else if (expectedStatus == 400) {
            assertEquals(0, count("domain_events"))
        } else if (command == "INSPECTION") {
            assertEquals(1, count("domain_events"))
            val inspectionId = response.body!!.path("inspectionId").asText()
            val eventId = db.queryForObject("SELECT id FROM domain_events WHERE hive_id=? AND event_type='InspectionRecordedEvent' AND payload->>'inspectionId'=?",
                UUID::class.java, hive, inspectionId)!!
            val expectedTransitions = if (initial == expectedState) 0 else 1
            assertEquals(expectedTransitions, db.queryForObject("SELECT count(*) FROM hive_state_transitions WHERE hive_id=? AND fsm_type='OPERATIONAL_LIFECYCLE'", Int::class.java, hive))
            if (expectedTransitions == 1) {
                assertEquals(1, db.queryForObject("""SELECT count(*) FROM hive_state_transitions s
                    JOIN domain_events e ON e.id=s.event_id WHERE s.hive_id=? AND e.hive_id=s.hive_id
                    AND s.event_id=? AND s.fsm_type='OPERATIONAL_LIFECYCLE'
                    AND s.from_state=? AND s.to_state=? AND s.transition_timestamp=e.occurred_at
                    AND e.occurred_at=?""", Int::class.java, hive, eventId, initial.name, expectedState.name, Timestamp.from(origin)))
            }
        }
    }

    @Test fun rejectionAuditRemainsCommittedAfterHttp409() {
        val hive = newHive(OperationalState.TREATMENT_IN_PROGRESS); condition(hive, "ADMINISTRATION")
        val response = post(hive, "HARVEST")
        assertEquals(409, response.statusCode.value())
        val id = UUID.fromString(response.body!!.path("eventId").asText())
        assertEquals(1, db.queryForObject("SELECT count(*) FROM domain_events e JOIN harvest_decisions d ON e.id=d.event_id JOIN decision_alerts a ON a.event_id=e.id JOIN hive_state_transitions t ON t.event_id=e.id WHERE e.id=? AND NOT d.approved", Int::class.java, id))
        assertEquals("TREATMENT_IN_PROGRESS", state(hive))
        val originalCounts = listOf("domain_events", "harvest_decisions", "decision_alerts", "hive_state_transitions").associateWith(::count)
        stateMachine.onHoneyHarvestAttempted(HoneyHarvestAttemptedEvent(eventId = id, hiveId = hive, occurredAt = origin))
        originalCounts.forEach { (table, size) -> assertEquals(size, count(table), "Redelivery must preserve $table cardinality") }
        assertEquals("TREATMENT_IN_PROGRESS", state(hive))
        assertEquals(false, db.queryForObject("SELECT approved FROM harvest_decisions WHERE event_id=?", Boolean::class.java, id))
        logBoundary("durable_rejection", hive, "HARVEST", response)
    }

    @ParameterizedTest(name = "wintering: courses={0}, inspections={1}, seconds from last expiry={2}")
    @MethodSource("winteringSequences")
    fun winteringPersistsAcrossTreatmentAndHarvest(courses: Int, inspections: Boolean, secondsFromLastExpiry: Long) {
        val hive = newHive(OperationalState.WINTERING)
        val observedStates = mutableListOf<String>()
        repeat(courses) { index ->
            // Each course has one day of administration and one day of withdrawal. The second starts 12 hours later.
            clock.time = origin.plusSeconds(index * 43200L)
            val treatment = post(hive, "TREATMENT")
            assertEquals(201, treatment.statusCode.value())
            observedStates += treatment.body!!.path("hiveOperationalState").asText()
            observedStates += state(hive)
        }
        fun inspect() {
            assertEquals(201, post(hive, "INSPECTION").statusCode.value())
            observedStates += state(hive)
        }
        if (inspections) inspect() // During administration.
        val lastExpiry = origin.plusSeconds((courses - 1) * 43200L + 172800L)
        clock.time = lastExpiry.plusSeconds(secondsFromLastExpiry)
        if (inspections) inspect()

        val harvest = post(hive, "HARVEST")
        val body = harvest.body!!
        val eventId = UUID.fromString(body.path("eventId").asText())
        observedStates += state(hive)
        val treatmentAudit = db.queryForObject("""SELECT count(*) FROM treatments t
            JOIN domain_events e ON e.hive_id=t.hive_id AND (e.payload->>'treatmentId')=t.id::text
            JOIN hive_state_transitions s ON s.event_id=e.id
            WHERE t.hive_id=? AND e.event_type='TreatmentAppliedEvent'
              AND s.from_state='WINTERING' AND s.to_state='WINTERING'""", Int::class.java, hive)
        val decisionAudit = db.queryForObject("""SELECT count(*) FROM domain_events e
            JOIN harvest_decisions d ON d.event_id=e.id JOIN hive_state_transitions s ON s.event_id=e.id
            WHERE e.id=? AND e.hive_id=? AND NOT d.approved AND d.reason_code='OPERATIONAL_STATE_BLOCKED'
              AND s.from_state='WINTERING' AND s.to_state='WINTERING'""", Int::class.java, eventId, hive)
        assertAll(
            { assertEquals(409, harvest.statusCode.value(), "Harvest must remain blocked during wintering") },
            { assertEquals("OPERATIONAL_STATE_BLOCKED", body.path("reasonCode").asText()) },
            { assertEquals("REJECTED", body.path("status").asText()) },
            { assertEquals(List(observedStates.size) { "WINTERING" }, observedStates, "Operational state at each command") },
            { assertEquals(courses, treatmentAudit, "A linked wintering self-transition for each recorded treatment") },
            { assertEquals(1, decisionAudit, "Committed event-linked rejection and wintering self-transition") },
            { assertEquals(0, db.queryForObject("SELECT count(*) FROM decision_alerts WHERE event_id=?", Int::class.java, eventId)) }
        )
    }

    @Test fun exactAdministrationAndWithdrawalBoundaries() {
        val hive = newHive()
        val applied = post(hive, "TREATMENT"); assertEquals(201, applied.statusCode.value())
        val adminEnd = origin.plusSeconds(86400); val withdrawalEnd = origin.plusSeconds(172800)
        for ((time, expectedState, status) in listOf(
            Triple(adminEnd.minusSeconds(1), "TREATMENT_IN_PROGRESS", 409),
            Triple(adminEnd, "WITHDRAWAL", 409),
            Triple(withdrawalEnd.minusSeconds(1), "WITHDRAWAL", 409),
            Triple(withdrawalEnd, "PREPARATION", 200),
            Triple(withdrawalEnd.plusSeconds(1), "ACTIVE_HONEY_FLOW", 200))) {
            clock.time = time
            val inspected = post(hive, "INSPECTION"); assertEquals(201, inspected.statusCode.value())
            assertEquals(expectedState, state(hive)); logBoundary("exact_boundary", hive, "INSPECTION", inspected)
            val harvested = post(hive, "HARVEST"); assertEquals(status, harvested.statusCode.value())
            logBoundary("exact_boundary", hive, "HARVEST", harvested)
        }
    }
    @Test fun serverTimestampsAgreeAtPostgresMicrosecondPrecision() {
        val hive = newHive()
        clock.time = origin.plusNanos(123456789)
        val expected = origin.plusNanos(123456000)
        val applied = post(hive, "TREATMENT")
        assertEquals(201, applied.statusCode.value())
        assertEquals(expected, Instant.parse(applied.body!!.path("startDate").asText()))
        assertEquals(expected, db.queryForObject("SELECT start_date FROM treatments WHERE hive_id=?", Timestamp::class.java, hive)!!.toInstant())
        val inspected = post(hive, "INSPECTION")
        assertEquals(201, inspected.statusCode.value())
        assertEquals(expected, Instant.parse(inspected.body!!.path("inspectionDate").asText()))
        val harvested = post(hive, "HARVEST")
        assertEquals(409, harvested.statusCode.value())
        assertEquals(expected, db.queryForObject("SELECT decided_at FROM harvest_decisions WHERE hive_id=?", Timestamp::class.java, hive)!!.toInstant())
        assertEquals(3, db.queryForObject("SELECT count(*) FROM domain_events WHERE (payload->>'occurredAt')::timestamptz = occurred_at AND occurred_at=?", Int::class.java, Timestamp.from(expected)))
    }
    @Test fun missedWithdrawalWindowAndZeroWithdrawalClearOnNextInspection() {
        val hive = newHive(); assertEquals(201, post(hive, "TREATMENT").statusCode.value())
        clock.time = origin.plusSeconds(172801)
        val response = post(hive, "INSPECTION"); assertEquals("PREPARATION", state(hive))
        logBoundary("missed_window", hive, "INSPECTION", response)
        val zero = http.postForEntity("/api/v1/hives/$hive/treatments", mapOf("medicineName" to "TEST", "activeSubstance" to "TEST", "durationDays" to 1, "withdrawalDays" to 0), JsonNode::class.java)
        assertEquals(201, zero.statusCode.value())
        clock.time = clock.time.plusSeconds(86400)
        val final = post(hive, "INSPECTION"); assertEquals("PREPARATION", state(hive))
        logBoundary("zero_withdrawal", hive, "INSPECTION", final)
    }
    @Test fun overlappingTreatmentsPreserveAdministrationUntilLastAdministrationEnds() {
        val hive = newHive(OperationalState.TREATMENT_IN_PROGRESS); condition(hive, "OVERLAP")
        var response = post(hive, "INSPECTION"); assertEquals("TREATMENT_IN_PROGRESS", state(hive))
        logBoundary("overlap", hive, "INSPECTION", response)
        clock.time = origin.plusSeconds(86400)
        response = post(hive, "INSPECTION"); assertEquals("WITHDRAWAL", state(hive)); logBoundary("overlap", hive, "INSPECTION", response)
        clock.time = origin.plusSeconds(172800)
        response = post(hive, "INSPECTION"); assertEquals("PREPARATION", state(hive)); logBoundary("overlap", hive, "INSPECTION", response)
    }

    @ParameterizedTest @MethodSource("invalidRequests")
    fun invalidInputDoesNotPersistAnything(endpoint: String, body: Map<String, Any>) {
        val hive = newHive()
        val response = http.postForEntity("/api/v1/hives/$hive/$endpoint", body, JsonNode::class.java)
        assertEquals(400, response.statusCode.value()); assertEquals(0, count("treatments"))
        assertEquals(0, count("domain_events")); assertEquals(0, count("harvest_decisions"))
    }
    @Test fun unexpectedFailureRollsBackAllPartialAuditAndState() {
        val hive = newHive(); failures.failNext.set(true)
        val response = post(hive, "HARVEST")
        assertEquals(500, response.statusCode.value()); assertEquals("PREPARATION", state(hive))
        listOf("domain_events", "harvest_decisions", "hive_state_transitions", "decision_alerts").forEach { assertEquals(0, count(it), it) }
        logBoundary("unexpected_failure_rollback", hive, "HARVEST", response)
    }
    @Test fun concurrentHarvestWaitsForTreatmentTransactionAndReadsItsCommit() {
        val hive = newHive(); val executor = Executors.newSingleThreadExecutor()
        try {
            lateinit var harvest: java.util.concurrent.Future<ResponseEntity<JsonNode>>
            TransactionTemplate(transactions).executeWithoutResult {
                hives.findByIdForUpdate(hive).orElseThrow()
                harvest = executor.submit<ResponseEntity<JsonNode>> { post(hive, "HARVEST") }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                var waiting = false
                while (System.nanoTime() < deadline) {
                    db.execute("SELECT pg_stat_clear_snapshot()")
                    waiting = db.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE '%hives%'", Int::class.java)!! > 0
                    if (waiting) break
                    Thread.sleep(20)
                }
                assertTrue(waiting, "The HTTP transaction must be observed waiting for the hive row lock")
                operations.applyTreatment(hive, ApplyTreatmentRequest(medicineName = "TEST", activeSubstance = "TEST", withdrawalDays = 1, durationDays = 1))
            }
            val response = harvest.get(10, TimeUnit.SECONDS)
            assertEquals(409, response.statusCode.value()); assertEquals(1, count("harvest_decisions"))
            logBoundary("concurrent_treatment_then_harvest", hive, "HARVEST", response)
        } finally { executor.shutdownNow() }
    }
}
