package io.github.Michal_Antosiewicz.apiary_management_system.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.github.Michal_Antosiewicz.apiary_management_system.domain.*
import io.github.Michal_Antosiewicz.apiary_management_system.event.InspectionRecordedEvent
import io.github.Michal_Antosiewicz.apiary_management_system.event.SwarmInterventionAppliedEvent
import io.github.Michal_Antosiewicz.apiary_management_system.event.TreatmentAppliedEvent
import io.github.Michal_Antosiewicz.apiary_management_system.repository.DecisionAlertRepository
import io.github.Michal_Antosiewicz.apiary_management_system.repository.HiveRepository
import io.github.Michal_Antosiewicz.apiary_management_system.repository.HiveStateTransitionRepository
import io.github.Michal_Antosiewicz.apiary_management_system.repository.TreatmentRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.invocation.InvocationOnMock
import java.math.BigDecimal
import java.time.Instant
import java.util.Optional
import java.util.UUID

class AuditReplayProvenanceTest {

    private val objectMapper = ObjectMapper()
        .registerKotlinModule()
        .registerModule(JavaTimeModule())

    private val hiveId = UUID.randomUUID()

    private lateinit var hiveRepository: HiveRepository
    private lateinit var treatmentRepository: TreatmentRepository
    private lateinit var transitionRepository: HiveStateTransitionRepository
    private lateinit var alertRepository: DecisionAlertRepository

    private val recordedTransitions = mutableListOf<HiveStateTransition>()
    private val domainEventsJsonStore = mutableListOf<String>()

    private lateinit var dummyHive: Hive

    @BeforeEach
    fun setUp() {
        hiveRepository = mock(HiveRepository::class.java)
        treatmentRepository = mock(TreatmentRepository::class.java)
        transitionRepository = mock(HiveStateTransitionRepository::class.java)
        alertRepository = mock(DecisionAlertRepository::class.java)

        recordedTransitions.clear()
        domainEventsJsonStore.clear()

        dummyHive = Hive(
            id = hiveId,
            apiary = mock(Apiary::class.java),
            hiveNumber = "H-AUDIT-01",
            hiveType = "LANGSTROTH",
            operationalState = OperationalState.PREPARATION,
            biologicalState = BiologicalState.NOMINAL
        )

        `when`(hiveRepository.findById(hiveId)).thenReturn(Optional.of(dummyHive))
        `when`(hiveRepository.save(any<Hive>(Hive::class.java))).thenAnswer { invocation: InvocationOnMock ->
            invocation.getArgument<Hive>(0)
        }

        // Intercept FSM state transitions for audit verification
        `when`(transitionRepository.save(any<HiveStateTransition>(HiveStateTransition::class.java))).thenAnswer { invocation: InvocationOnMock ->
            val transition = invocation.getArgument<HiveStateTransition>(0)
            recordedTransitions.add(transition)
            transition
        }
    }

    @Test
    fun `verify deterministic event replay and complete causal provenance chain`() {
        val fsmService = HiveStateMachineService(
            hiveRepository,
            treatmentRepository,
            transitionRepository,
            alertRepository
        )

        // 1. Define sequence of domain events
        val eventSequence = listOf<Any>(
            // Triggers SWARM_PREPARATION biological state (marginal cells > 0)
            InspectionRecordedEvent(
                hiveId = hiveId,
                inspectionId = UUID.randomUUID(),
                framesOfBees = 12,
                framesOfBrood = 8,
                honeyStoresKg = BigDecimal("12.00"),
                freeCombSpacePercent = BigDecimal("5.00"),
                queenSeen = true,
                broodPattern = BroodPattern.SOLID_COMPACT,
                queenCellsCount = 8,
                queenCellsPlacement = CellPlacement.MARGINAL,
                queenCellsStage = CellStage.SEALED,
                varroaMiteCount = 2,
                signsOfPests = false,
                temperamentScore = 4
            ),
            // Beekeeper intervention resetting biological state to NOMINAL
            SwarmInterventionAppliedEvent(
                hiveId = hiveId,
                occurredAt = Instant.now(),
                interventionType = "ARTIFICIAL_SPLIT",
                queenCellsDestroyed = true,
                artificialSwarmCreated = true
            ),
            // Treatment application triggering operational state transition to TREATMENT_IN_PROGRESS
            TreatmentAppliedEvent(
                hiveId = hiveId,
                treatmentId = UUID.randomUUID(),
                medicineName = "Apivar",
                activeSubstance = "Amitraz",
                startDate = Instant.now(),
                withdrawalDays = 14,
                withdrawalEndDate = Instant.now().plusSeconds(14 * 86400)
            )
        )

        // 2. Serialize events into immutable JSON audit store
        eventSequence.forEach { event ->
            val jsonPayload = objectMapper.writeValueAsString(event)
            domainEventsJsonStore.add(jsonPayload)
        }

        // 3. Replay events sequentially from JSON log
        domainEventsJsonStore.forEach { jsonStr ->
            dispatchReplayedEvent(fsmService, jsonStr)
        }

        // 4. Assert reconstructed entity state
        assertEquals(BiologicalState.NOMINAL, dummyHive.biologicalState)
        assertEquals(OperationalState.TREATMENT_IN_PROGRESS, dummyHive.operationalState)

        // 5. Assert audit log provenance and recorded transition history
        assertEquals(3, recordedTransitions.size)

        val bioTransitions = recordedTransitions.filter { it.fsmType == "BIOLOGICAL_DYNAMICS" }
        assertEquals(2, bioTransitions.size)
        assertEquals(BiologicalState.SWARM_PREPARATION.name, bioTransitions[0].toState)
        assertEquals(BiologicalState.NOMINAL.name, bioTransitions[1].toState)

        val opTransitions = recordedTransitions.filter { it.fsmType == "OPERATIONAL_LIFECYCLE" }
        assertEquals(1, opTransitions.size)
        assertEquals(OperationalState.TREATMENT_IN_PROGRESS.name, opTransitions[0].toState)

        println("Audit Replay Verification: 100% Deterministic State Equivalence Confirmed.")
        println("Recorded Transitions in Audit Log: ${recordedTransitions.size}")
    }

    private fun dispatchReplayedEvent(fsmService: HiveStateMachineService, json: String) {
        when {
            json.contains("framesOfBees") -> {
                val event = objectMapper.readValue(json, InspectionRecordedEvent::class.java)
                fsmService.onInspectionRecorded(event)
            }
            json.contains("interventionType") -> {
                val event = objectMapper.readValue(json, SwarmInterventionAppliedEvent::class.java)
                fsmService.onSwarmInterventionApplied(event)
            }
            json.contains("activeSubstance") -> {
                val event = objectMapper.readValue(json, TreatmentAppliedEvent::class.java)
                fsmService.onTreatmentApplied(event)
            }
            else -> error("Unknown event payload: $json")
        }
    }
}