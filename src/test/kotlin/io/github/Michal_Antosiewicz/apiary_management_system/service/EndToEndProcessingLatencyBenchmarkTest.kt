package io.github.Michal_Antosiewicz.apiary_management_system.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.github.Michal_Antosiewicz.apiary_management_system.domain.*
import io.github.Michal_Antosiewicz.apiary_management_system.event.InspectionRecordedEvent
import io.github.Michal_Antosiewicz.apiary_management_system.repository.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.invocation.InvocationOnMock
import java.math.BigDecimal
import java.util.Optional
import java.util.UUID

class EndToEndProcessingLatencyBenchmarkTest {

    private lateinit var hiveRepository: HiveRepository
    private lateinit var queenRepository: QueenRepository
    private lateinit var alertRepository: DecisionAlertRepository
    private lateinit var treatmentRepository: TreatmentRepository
    private lateinit var transitionRepository: HiveStateTransitionRepository

    private lateinit var fsmService: HiveStateMachineService
    private lateinit var decisionEngine: DecisionSupportEngine

    private val objectMapper = ObjectMapper()
        .registerKotlinModule()
        .registerModule(JavaTimeModule())

    @BeforeEach
    fun setUp() {
        hiveRepository = mock(HiveRepository::class.java)
        queenRepository = mock(QueenRepository::class.java)
        alertRepository = mock(DecisionAlertRepository::class.java)
        treatmentRepository = mock(TreatmentRepository::class.java)
        transitionRepository = mock(HiveStateTransitionRepository::class.java)

        `when`(alertRepository.save(any<DecisionAlert>(DecisionAlert::class.java))).thenAnswer { invocation: InvocationOnMock ->
            invocation.getArgument<DecisionAlert>(0)
        }
        `when`(hiveRepository.save(any<Hive>(Hive::class.java))).thenAnswer { invocation: InvocationOnMock ->
            invocation.getArgument<Hive>(0)
        }
        `when`(transitionRepository.save(any<HiveStateTransition>(HiveStateTransition::class.java))).thenAnswer { invocation: InvocationOnMock ->
            invocation.getArgument<HiveStateTransition>(0)
        }

        decisionEngine = DecisionSupportEngine(hiveRepository, queenRepository, alertRepository)
        fsmService = HiveStateMachineService(
            hiveRepository,
            treatmentRepository,
            transitionRepository,
            alertRepository
        )
    }

    @Test
    fun `benchmark end-to-end event intake and inference latency across N=1000 events`() {
        val n = 1000
        val warmupIterations = 200
        val latenciesMicros = LongArray(n)

        val hiveUuid = UUID.randomUUID()

        val dummyApiary = mock(Apiary::class.java)
        val dummyHive = Hive(
            id = hiveUuid,
            apiary = dummyApiary,
            hiveNumber = "H-BENCH",
            hiveType = "LANGSTROTH"
        )

        val dummyQueen = Queen(
            id = UUID.randomUUID(),
            hive = dummyHive,
            emergenceYear = 2024,
            breedLine = "Carnica",
            isActive = true
        )

        `when`(hiveRepository.findById(hiveUuid)).thenReturn(Optional.of(dummyHive))
        `when`(queenRepository.findByHiveIdAndIsActiveTrue(hiveUuid)).thenReturn(Optional.of(dummyQueen))

        // Pre-generate benchmark event payloads
        val testEvents = (0 until (n + warmupIterations)).map { i ->
            InspectionRecordedEvent(
                hiveId = hiveUuid,
                inspectionId = UUID.randomUUID(),
                framesOfBees = 8 + (i % 4),
                framesOfBrood = 5 + (i % 3),
                honeyStoresKg = BigDecimal("6.50"),
                freeCombSpacePercent = BigDecimal("25.00"),
                queenSeen = true,
                broodPattern = BroodPattern.SOLID_COMPACT,
                queenCellsCount = i % 5,
                queenCellsPlacement = if (i % 2 == 0) CellPlacement.MARGINAL else CellPlacement.CENTRAL,
                queenCellsStage = CellStage.OPEN_WITH_LARVA,
                varroaMiteCount = i % 15,
                signsOfPests = false,
                temperamentScore = 4
            )
        }

        // JVM warmup phase
        for (i in 0 until warmupIterations) {
            val event = testEvents[i]
            objectMapper.writeValueAsString(event)
            decisionEngine.handleInspection(event)
            fsmService.onInspectionRecorded(event)
        }

        // Measured benchmark execution phase
        for (i in 0 until n) {
            val event = testEvents[warmupIterations + i]
            val startTime = System.nanoTime()

            // 1. JSON serialization (event log write simulation)
            objectMapper.writeValueAsString(event)

            // 2. Rule engine / decision support inference
            decisionEngine.handleInspection(event)

            // 3. Hive state machine transition & audit logging
            fsmService.onInspectionRecorded(event)

            val durationMicros = (System.nanoTime() - startTime) / 1000
            latenciesMicros[i] = durationMicros
        }

        latenciesMicros.sort()

        val meanMicros = latenciesMicros.average()
        val medianMicros = latenciesMicros[n / 2]
        val p95Micros = latenciesMicros[(n * 0.95).toInt()]
        val p99Micros = latenciesMicros[(n * 0.99).toInt()]
        val throughputPerSec = (1_000_000.0 / meanMicros).toInt()

        println("\n=======================================================")
        println("END-TO-END PROCESSING LATENCY BENCHMARK (N = $n)")
        println("=======================================================")
        println("Mean Latency:       %8.2f µs (%.3f ms)".format(meanMicros, meanMicros / 1000.0))
        println("Median (p50):       %8d µs (%.3f ms)".format(medianMicros, medianMicros / 1000.0))
        println("95th Percentile:    %8d µs (%.3f ms)".format(p95Micros, p95Micros / 1000.0))
        println("99th Percentile:    %8d µs (%.3f ms)".format(p99Micros, p99Micros / 1000.0))
        println("Estimated Throughput: %6d events / second".format(throughputPerSec))
        println("=======================================================\n")
    }
}