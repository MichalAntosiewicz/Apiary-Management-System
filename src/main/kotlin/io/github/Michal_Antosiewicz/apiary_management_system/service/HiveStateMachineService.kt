package io.github.Michal_Antosiewicz.apiary_management_system.service

import io.github.Michal_Antosiewicz.apiary_management_system.domain.*
import io.github.Michal_Antosiewicz.apiary_management_system.event.*
import io.github.Michal_Antosiewicz.apiary_management_system.repository.DecisionAlertRepository
import io.github.Michal_Antosiewicz.apiary_management_system.repository.HiveRepository
import io.github.Michal_Antosiewicz.apiary_management_system.repository.HiveStateTransitionRepository
import io.github.Michal_Antosiewicz.apiary_management_system.repository.TreatmentRepository
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant

@Service
class HiveStateMachineService(
    private val hiveRepository: HiveRepository,
    private val treatmentRepository: TreatmentRepository,
    private val transitionRepository: HiveStateTransitionRepository,
    private val alertRepository: DecisionAlertRepository
) {

    // Handles biological state transitions triggered by field inspection data
    @EventListener
    @Transactional
    fun onInspectionRecorded(event: InspectionRecordedEvent) {
        val hive = hiveRepository.findById(event.hiveId).orElse(null) ?: return
        val currentBioState = hive.biologicalState

        val targetBioState = when {
            // Swarm preparation trigger: marginal cells present
            event.queenCellsPlacement == CellPlacement.MARGINAL && event.queenCellsCount > 0 ->
                BiologicalState.SWARM_PREPARATION

            // Supersedure trigger: central cells with low quantity
            event.queenCellsPlacement == CellPlacement.CENTRAL && event.queenCellsCount in 1..4 ->
                BiologicalState.SUPERSEDURE_ACTIVE

            // Absconding risk trigger: extreme parasite load or complete starvation
            event.varroaMiteCount >= 25 || (event.honeyStoresKg < BigDecimal("1.50") && event.signsOfPests) ->
                BiologicalState.ABSCONDING_RISK

            // Congestion precursor
            event.freeCombSpacePercent <= BigDecimal("10.0") && currentBioState == BiologicalState.NOMINAL ->
                BiologicalState.CONGESTED

            // Fallback recovery if previous conditions subsided
            currentBioState in listOf(BiologicalState.CONGESTED, BiologicalState.SWARM_PREPARATION) &&
                    event.queenCellsCount == 0 && event.freeCombSpacePercent > BigDecimal("20.0") ->
                BiologicalState.NOMINAL

            else -> currentBioState
        }

        if (currentBioState != targetBioState) {
            recordTransition(
                hive = hive,
                fsmType = "BIOLOGICAL_DYNAMICS",
                fromState = currentBioState.name,
                toState = targetBioState.name,
                triggerEvent = "InspectionRecordedEvent",
                reason = "Automated biological state transition based on inspection indicators"
            )
            hive.biologicalState = targetBioState
            hiveRepository.save(hive)
        }
    }

    // Handles operational state transition when veterinary treatment is initiated
    @EventListener
    @Transactional
    fun onTreatmentApplied(event: TreatmentAppliedEvent) {
        val hive = hiveRepository.findById(event.hiveId).orElse(null) ?: return
        val previousState = hive.operationalState

        recordTransition(
            hive = hive,
            fsmType = "OPERATIONAL_LIFECYCLE",
            fromState = previousState.name,
            toState = OperationalState.TREATMENT_IN_PROGRESS.name,
            triggerEvent = "TreatmentAppliedEvent",
            reason = "Administered ${event.medicineName}; withdrawal period set until ${event.withdrawalEndDate}"
        )

        hive.operationalState = OperationalState.TREATMENT_IN_PROGRESS
        hiveRepository.save(hive)
    }

    // Sanitary Guard: validates honey harvesting attempts against active treatment withdrawal periods
    @EventListener
    @Transactional
    fun onHoneyHarvestAttempted(event: HoneyHarvestAttemptedEvent) {
        val hive = hiveRepository.findById(event.hiveId).orElse(null) ?: return
        val now = Instant.now()
        val activeTreatments = treatmentRepository.findActiveTreatmentsByHiveId(hive.id, now)

        if (activeTreatments.isNotEmpty()) {
            val conflictingTreatment = activeTreatments.first()

            // Record sanitary violation alert
            alertRepository.save(
                DecisionAlert(
                    hive = hive,
                    alertType = AlertType.SANITARY_VIOLATION,
                    severity = AlertSeverity.CRITICAL,
                    riskScore = BigDecimal("1.000"),
                    recommendationText = "CRITICAL: Honey harvest blocked. Hive is under active withdrawal for ${conflictingTreatment.medicineName} until ${conflictingTreatment.withdrawalEndDate}."
                )
            )

            // Audit rejected transition attempt
            recordTransition(
                hive = hive,
                fsmType = "OPERATIONAL_LIFECYCLE",
                fromState = hive.operationalState.name,
                toState = hive.operationalState.name,
                triggerEvent = "HoneyHarvestAttemptedEvent",
                reason = "REJECTED: Active withdrawal restriction for ${conflictingTreatment.medicineName}"
            )

            throw SanitaryViolationException(
                hiveId = hive.id,
                medicineName = conflictingTreatment.medicineName,
                withdrawalEndDate = conflictingTreatment.withdrawalEndDate
            )
        }

        // Guard passed: allow transition to honey flow if not already active
        if (hive.operationalState != OperationalState.ACTIVE_HONEY_FLOW) {
            recordTransition(
                hive = hive,
                fsmType = "OPERATIONAL_LIFECYCLE",
                fromState = hive.operationalState.name,
                toState = OperationalState.ACTIVE_HONEY_FLOW.name,
                triggerEvent = "HoneyHarvestAttemptedEvent",
                reason = "Sanitary guard cleared. Transitioned to active harvest state"
            )
            hive.operationalState = OperationalState.ACTIVE_HONEY_FLOW
            hiveRepository.save(hive)
        }
    }

    //Resolves swarm preparation state following successful beekeeper intervention.
    @EventListener
    @Transactional
    fun onSwarmInterventionApplied(event: SwarmInterventionAppliedEvent) {
        val hive = hiveRepository.findById(event.hiveId).orElse(null) ?: return

        if (hive.biologicalState == BiologicalState.SWARM_PREPARATION) {
            recordTransition(
                hive = hive,
                fsmType = "BIOLOGICAL_DYNAMICS",
                fromState = hive.biologicalState.name,
                toState = BiologicalState.NOMINAL.name,
                triggerEvent = "SwarmInterventionAppliedEvent",
                reason = "Swarm intervention applied (${event.interventionType}); state reset to nominal"
            )
            hive.biologicalState = BiologicalState.NOMINAL
            hiveRepository.save(hive)
        }
    }

    //Concludes supersedure cycle upon verified queen replacement.
    @EventListener
    @Transactional
    fun onQueenReplaced(event: QueenReplacedEvent) {
        val hive = hiveRepository.findById(event.hiveId).orElse(null) ?: return

        if (hive.biologicalState == BiologicalState.SUPERSEDURE_ACTIVE) {
            recordTransition(
                hive = hive,
                fsmType = "BIOLOGICAL_DYNAMICS",
                fromState = hive.biologicalState.name,
                toState = BiologicalState.NOMINAL.name,
                triggerEvent = "QueenReplacedEvent",
                reason = "New queen installed (${event.breedLine}); supersedure cycle concluded"
            )
            hive.biologicalState = BiologicalState.NOMINAL
            hiveRepository.save(hive)
        }
    }

    private fun recordTransition(
        hive: Hive,
        fsmType: String,
        fromState: String,
        toState: String,
        triggerEvent: String,
        reason: String
    ) {
        transitionRepository.save(
            HiveStateTransition(
                hive = hive,
                fsmType = fsmType,
                fromState = fromState,
                toState = toState,
                triggerEvent = triggerEvent,
                reason = reason,
                transitionTimestamp = Instant.now()
            )
        )
    }
}