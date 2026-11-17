package io.github.Michal_Antosiewicz.apiary_management_system

import com.fasterxml.jackson.databind.JsonNode
import io.github.Michal_Antosiewicz.apiary_management_system.domain.OperationalState
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.stream.Stream

/** Compares one decision on a shared fixture. Only the interval policy executes the production HTTP path. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.docker.compose.enabled=false", "spring.jpa.open-in-view=false", "spring.jpa.show-sql=false"])
@Import(WorkflowDesignComparisonTest.TestBeans::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkflowDesignComparisonTest {
    companion object {
        private val origin = Instant.parse("2026-01-01T12:00:00Z")
        private val postgres = EmbeddedPostgres.builder().setPort(0)
            .setServerConfig("listen_addresses", "127.0.0.1").start()

        @JvmStatic @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.getJdbcUrl("postgres", "postgres") }
            registry.add("spring.datasource.username") { "postgres" }
            registry.add("spring.datasource.password") { "postgres" }
        }

        private fun rows(resource: String): List<Map<String, String>> {
            val lines = WorkflowDesignComparisonTest::class.java.getResourceAsStream(resource)!!
                .bufferedReader().use { it.readLines() }.filter { it.isNotBlank() }
            val header = lines.first().split(',')
            return lines.drop(1).map { line ->
                val fields = line.split(',')
                require(fields.size == header.size) { "Invalid case row in $resource" }
                header.zip(fields).toMap()
            }
        }

        @JvmStatic fun comparisonCases(): Stream<Arguments> = rows("/workflow_design_cases.csv")
            .map { Arguments.of(it.getValue("case_id"), it) }.stream()

        @JvmStatic fun transferCases(): Stream<Arguments> = rows("/batch_transfer_cases.csv")
            .map { Arguments.of(it.getValue("case_id"), it) }.stream()
    }

    class MutableClock : Clock() {
        @Volatile var time: Instant = origin
        override fun instant(): Instant = time
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(time, zone)
    }

    @TestConfiguration
    class TestBeans {
        @Bean @Primary fun comparisonClock() = MutableClock()
    }

    @Autowired lateinit var http: TestRestTemplate
    @Autowired lateinit var database: JdbcTemplate
    @Autowired lateinit var clock: MutableClock
    private val comparisons = sortedMapOf<String, String>()
    private val transfers = sortedMapOf<String, String>()

    @BeforeEach
    fun resetFixture() {
        database.execute("TRUNCATE apiaries CASCADE")
        clock.time = origin
    }

    @AfterAll
    fun exportAndStopDatabase() {
        val output = Path.of("build", "verification")
        Files.createDirectories(output)
        Files.write(output.resolve("workflow-design-comparison.csv"), listOf(
            "case_id,initial_state,treatments,refresh,time_seconds,stored_phase,expected_interval_approval,interval_approval,expected_cached_approval,cached_approval,http_status,audit_verified"
        ) + comparisons.values)
        Files.write(output.resolve("batch-transfer.csv"), listOf(
            "case_id,batch_state,hold_intervals_seconds,time_seconds,expected_release,actual_release,http_status,audit_verified"
        ) + transfers.values)
        postgres.close()
    }

    private fun createHive(state: OperationalState): UUID {
        val apiaryId = UUID.randomUUID()
        val hiveId = UUID.randomUUID()
        database.update("INSERT INTO apiaries(id,name,latitude,longitude) VALUES (?,?,0,0)", apiaryId, "Design comparison fixture")
        database.update("INSERT INTO hives(id,apiary_id,hive_number,hive_type,operational_state) VALUES (?,?,?,?,?::operational_state_enum)",
            hiveId, apiaryId, "COMPARISON", "TEST", state.name)
        return hiveId
    }

    private fun storedState(hiveId: UUID): OperationalState = OperationalState.valueOf(database.queryForObject(
        "SELECT operational_state::text FROM hives WHERE id=?", String::class.java, hiveId)!!)

    private fun recordTreatment(hiveId: UUID, startSeconds: Long, withdrawalDays: Int) {
        clock.time = origin.plusSeconds(startSeconds)
        val response = http.postForEntity("/api/v1/hives/$hiveId/treatments", mapOf(
            "medicineName" to "TEST", "activeSubstance" to "TEST", "durationDays" to 1,
            "withdrawalDays" to withdrawalDays), JsonNode::class.java)
        assertEquals(201, response.statusCode.value())
    }

    private fun inspect(hiveId: UUID) {
        val response = http.postForEntity("/api/v1/inspections", mapOf(
            "hiveId" to hiveId, "framesOfBees" to 8, "framesOfBrood" to 4, "honeyStoresKg" to 8,
            "freeCombSpacePercent" to 30, "queenSeen" to true, "broodPattern" to "SOLID_COMPACT"), JsonNode::class.java)
        assertEquals(201, response.statusCode.value())
    }

    private fun harvest(hiveId: UUID): ResponseEntity<JsonNode> = http.postForEntity(
        "/api/v1/hives/$hiveId/harvest", mapOf("targetSuperCount" to 1), JsonNode::class.java)

    private fun cachedPhaseAllowsHarvest(phase: OperationalState): Boolean = phase !in setOf(
        OperationalState.WINTERING, OperationalState.DECOMMISSIONED,
        OperationalState.TREATMENT_IN_PROGRESS, OperationalState.WITHDRAWAL)

    private fun verifyProductionDecision(hiveId: UUID, response: ResponseEntity<JsonNode>, expectedApproval: Boolean, before: OperationalState) {
        assertEquals(if (expectedApproval) 200 else 409, response.statusCode.value())
        val body = response.body!!
        assertEquals(if (expectedApproval) "APPROVED" else "REJECTED", body.path("status").asText())
        val eventId = UUID.fromString(body.path("eventId").asText())
        val after = if (expectedApproval) OperationalState.ACTIVE_HONEY_FLOW else before
        assertEquals(after, storedState(hiveId))
        val linkedDecisions = database.queryForObject("""SELECT count(*) FROM domain_events e
            JOIN harvest_decisions d ON d.event_id=e.id JOIN hive_state_transitions s ON s.event_id=e.id
            WHERE e.id=? AND e.hive_id=? AND d.hive_id=e.hive_id AND s.hive_id=e.hive_id
              AND e.event_type='HoneyHarvestAttemptedEvent' AND d.approved=?
              AND e.occurred_at=? AND d.decided_at=e.occurred_at AND s.transition_timestamp=e.occurred_at
              AND (e.payload->>'occurredAt')::timestamptz=e.occurred_at
              AND s.from_state=? AND s.to_state=?""", Int::class.java,
            eventId, hiveId, expectedApproval, Timestamp.from(clock.time), before.name, after.name)
        assertEquals(1, linkedDecisions, "Committed event, decision and transition with the common command timestamp")
    }

    @ParameterizedTest(name = "design comparison {0}")
    @MethodSource("comparisonCases")
    fun compareCachedPhaseWithTransactionTimeIntervals(caseId: String, row: Map<String, String>) {
        val initial = OperationalState.valueOf(row.getValue("initial_state"))
        val hiveId = createHive(initial)
        when (row.getValue("treatments")) {
            "SINGLE" -> recordTreatment(hiveId, 0, 1)
            "ZERO_WITHDRAWAL" -> recordTreatment(hiveId, 0, 0)
            "OVERLAP" -> {
                recordTreatment(hiveId, 0, 1)
                recordTreatment(hiveId, 43200, 1)
            }
            "NONE" -> Unit
            else -> error("Unknown treatment fixture")
        }
        clock.time = origin.plusSeconds(row.getValue("time_seconds").toLong())
        if (row.getValue("refresh").toBooleanStrict()) inspect(hiveId)
        val before = storedState(hiveId)
        assertEquals(row.getValue("expected_stored_phase"), before.name)
        // No command or other writer intervenes between this snapshot and the single harvest request.
        val cachedApproval = cachedPhaseAllowsHarvest(before)
        val response = harvest(hiveId)
        val expectedInterval = row.getValue("expected_interval_approval").toBooleanStrict()
        val expectedCached = row.getValue("expected_cached_approval").toBooleanStrict()
        verifyProductionDecision(hiveId, response, expectedInterval, before)
        assertEquals(expectedCached, cachedApproval, "The reference policy must satisfy its own stored-phase contract")
        comparisons[caseId] = listOf(caseId, initial.name, row.getValue("treatments"), row.getValue("refresh"),
            row.getValue("time_seconds"), before.name, expectedInterval, response.body!!.path("status").asText() == "APPROVED",
            expectedCached, cachedApproval, response.statusCode.value(), true).joinToString(",")
    }

    @ParameterizedTest(name = "batch transfer {0}")
    @MethodSource("transferCases")
    fun transferHoldIntervalsThroughProductionGuard(caseId: String, row: Map<String, String>) {
        val batchState = row.getValue("batch_state")
        val initial = when (batchState) {
            "AVAILABLE" -> OperationalState.PREPARATION
            "SUSPENDED" -> OperationalState.WINTERING
            "RETIRED" -> OperationalState.DECOMMISSIONED
            else -> error("Unknown batch lifecycle state")
        }
        val hiveId = createHive(initial)
        val holds = row.getValue("hold_intervals_seconds")
        if (holds != "NONE") {
            for (interval in holds.split('|')) {
                val (start, end) = interval.split(':').map { origin.plusSeconds(it.toLong()) }
                // A generic hold maps to a treatment interval with zero additional withdrawal days.
                database.update("""INSERT INTO treatments(id,hive_id,medicine_name,active_substance,
                    start_date,treatment_end_date,withdrawal_days,withdrawal_end_date) VALUES (?,?,?,?,?,?,0,?)""",
                    UUID.randomUUID(), hiveId, "GENERIC HOLD", "TEST ADAPTER", Timestamp.from(start), Timestamp.from(end), Timestamp.from(end))
            }
        }
        clock.time = origin.plusSeconds(row.getValue("time_seconds").toLong())
        val response = harvest(hiveId)
        val expectedRelease = row.getValue("expected_release").toBooleanStrict()
        verifyProductionDecision(hiveId, response, expectedRelease, initial)
        transfers[caseId] = listOf(caseId, batchState, holds, row.getValue("time_seconds"), expectedRelease,
            response.body!!.path("status").asText() == "APPROVED", response.statusCode.value(), true).joinToString(",")
    }
}
