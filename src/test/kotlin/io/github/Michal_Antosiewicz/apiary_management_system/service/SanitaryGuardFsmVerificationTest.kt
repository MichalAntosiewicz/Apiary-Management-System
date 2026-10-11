package io.github.Michal_Antosiewicz.apiary_management_system.service

import io.github.Michal_Antosiewicz.apiary_management_system.domain.*
import io.github.Michal_Antosiewicz.apiary_management_system.event.HoneyHarvestAttemptedEvent
import io.github.Michal_Antosiewicz.apiary_management_system.event.TreatmentAppliedEvent
import io.github.Michal_Antosiewicz.apiary_management_system.repository.DecisionAlertRepository
import io.github.Michal_Antosiewicz.apiary_management_system.repository.HiveRepository
import io.github.Michal_Antosiewicz.apiary_management_system.repository.HiveStateTransitionRepository
import io.github.Michal_Antosiewicz.apiary_management_system.repository.TreatmentRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Optional
import java.util.UUID

class SanitaryGuardFsmVerificationTest {

    private lateinit var hiveRepository: HiveRepository
    private lateinit var treatmentRepository: TreatmentRepository
    private lateinit var transitionRepository: HiveStateTransitionRepository
    private lateinit var alertRepository: DecisionAlertRepository
    private lateinit var fsmService: HiveStateMachineService

    private val hiveId = UUID.randomUUID()
    private val now = Instant.now()
    private lateinit var dummyHive: Hive

    @BeforeEach
    fun setUp() {
        hiveRepository = mock()
        treatmentRepository = mock()
        transitionRepository = mock()
        alertRepository = mock()

        dummyHive = Hive(
            id = hiveId,
            apiary = mock(),
            hiveNumber = "H-01",
            hiveType = "LANGSTROTH",
            operationalState = OperationalState.PREPARATION,
            biologicalState = BiologicalState.NOMINAL
        )

        whenever(hiveRepository.findById(any())).thenReturn(Optional.of(dummyHive))
        whenever(hiveRepository.save(any<Hive>())).thenAnswer { it.getArgument<Hive>(0) }
        whenever(transitionRepository.save(any<HiveStateTransition>())).thenAnswer { it.getArgument<HiveStateTransition>(0) }
        whenever(alertRepository.save(any<DecisionAlert>())).thenAnswer { it.getArgument<DecisionAlert>(0) }

        // Default: zero active treatments returned
        whenever(treatmentRepository.findActiveTreatmentsByHiveId(any(), any())).thenReturn(emptyList())

        fsmService = HiveStateMachineService(
            hiveRepository,
            treatmentRepository,
            transitionRepository,
            alertRepository
        )
    }

    @Test
    fun `scenario 1 - should reject harvest during active medical treatment and throw SanitaryViolationException`() {
        val activeTreatment = mock<Treatment> {
            on { medicineName } doReturn "Apivar"
            on { withdrawalEndDate } doReturn now.plus(14, ChronoUnit.DAYS)
        }

        whenever(treatmentRepository.findActiveTreatmentsByHiveId(any(), any()))
            .thenReturn(listOf(activeTreatment))

        val harvestEvent = HoneyHarvestAttemptedEvent(
            hiveId = hiveId,
            occurredAt = now.plus(5, ChronoUnit.DAYS),
            targetSuperCount = 2
        )

        val exception = assertThrows(SanitaryViolationException::class.java) {
            fsmService.onHoneyHarvestAttempted(harvestEvent)
        }

        assertEquals("Apivar", exception.medicineName)
        assertEquals(dummyHive.id, exception.hiveId)
        verify(alertRepository).save(any<DecisionAlert>())
        verify(transitionRepository).save(any<HiveStateTransition>())
    }

    @Test
    fun `scenario 2 - should reject harvest during statutory withdrawal window`() {
        val activeTreatment = mock<Treatment> {
            on { medicineName } doReturn "Bayvarol"
            on { withdrawalEndDate } doReturn now.plus(28, ChronoUnit.DAYS)
        }

        whenever(treatmentRepository.findActiveTreatmentsByHiveId(any(), any()))
            .thenReturn(listOf(activeTreatment))

        val harvestEvent = HoneyHarvestAttemptedEvent(
            hiveId = hiveId,
            occurredAt = now.plus(20, ChronoUnit.DAYS),
            targetSuperCount = 1
        )

        val exception = assertThrows(SanitaryViolationException::class.java) {
            fsmService.onHoneyHarvestAttempted(harvestEvent)
        }

        assertEquals("Bayvarol", exception.medicineName)
        verify(alertRepository).save(any<DecisionAlert>())
    }

    @Test
    fun `scenario 3 - should accept harvest when sanitary guard cleared with zero active treatments`() {
        val harvestEvent = HoneyHarvestAttemptedEvent(
            hiveId = hiveId,
            occurredAt = now.plus(15, ChronoUnit.DAYS),
            targetSuperCount = 3
        )

        assertDoesNotThrow {
            fsmService.onHoneyHarvestAttempted(harvestEvent)
        }

        assertEquals(OperationalState.ACTIVE_HONEY_FLOW, dummyHive.operationalState)
        verify(hiveRepository).save(dummyHive)
        verify(transitionRepository).save(any<HiveStateTransition>())
    }

    @Test
    fun `scenario 4 - should transition to TREATMENT_IN_PROGRESS upon treatment applied`() {
        val treatmentEvent = TreatmentAppliedEvent(
            hiveId = hiveId,
            treatmentId = UUID.randomUUID(),
            medicineName = "FormicPro",
            activeSubstance = "Formic Acid",
            startDate = now,
            withdrawalDays = 0,
            withdrawalEndDate = now.plus(7, ChronoUnit.DAYS)
        )

        fsmService.onTreatmentApplied(treatmentEvent)

        assertEquals(OperationalState.TREATMENT_IN_PROGRESS, dummyHive.operationalState)
        verify(hiveRepository).save(dummyHive)
        verify(transitionRepository).save(any<HiveStateTransition>())
    }
}