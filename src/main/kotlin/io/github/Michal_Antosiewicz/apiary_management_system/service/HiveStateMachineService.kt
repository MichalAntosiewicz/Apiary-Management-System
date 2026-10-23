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

    // Handles biological and operational state transitions triggered by field inspection data
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

            // Stress and absconding recovery when parasite and store indicators normalize
            currentBioState == BiologicalState.ABSCONDING_RISK &&
                    event.varroaMiteCount < 10 &&
                    event.honeyStoresKg >= BigDecimal("3.00") &&
                    !event.signsOfPests ->
                BiologicalState.NOMINAL

            // Congestion recovery; SWARM_PREPARATION resolves only via swarm intervention
            currentBioState == BiologicalState.CONGESTED &&
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
                event = event,
                reason = "Automated biological state transition based on inspection indicators"
            )
            hive.biologicalState = targetBioState
            hiveRepository.save(hive)
        }

        evaluateOperationalStateOnInspection(hive, event)
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
            event = event,
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
                event = event,
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
                event = event,
                reason = "Sanitary guard cleared. Transitioned to active harvest state"
            )
            hive.operationalState = OperationalState.ACTIVE_HONEY_FLOW
            hiveRepository.save(hive)
        }
    }

    // Resolves swarm preparation state following successful beekeeper intervention
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
                event = event,
                reason = "Swarm intervention applied (${event.interventionType}); state reset to nominal"
            )
            hive.biologicalState = BiologicalState.NOMINAL
            hiveRepository.save(hive)
        }
    }

    // Concludes supersedure cycle upon verified queen replacement
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
                event = event,
                reason = "New queen installed (${event.breedLine}); supersedure cycle concluded"
            )
            hive.biologicalState = BiologicalState.NOMINAL
            hiveRepository.save(hive)
        }
    }

    private fun evaluateOperationalStateOnInspection(hive: Hive, event: InspectionRecordedEvent) {
        val now = Instant.now()
        val activeTreatments = treatmentRepository.findActiveTreatmentsByHiveId(hive.id, now)

        // Rule A: treatment administration ended while withdrawal remains active
        if (hive.operationalState == OperationalState.TREATMENT_IN_PROGRESS) {
            val treatmentPastAdmin = activeTreatments.firstOrNull { treatment ->
                !now.isBefore(treatment.treatmentEndDate) && now.isBefore(treatment.withdrawalEndDate)
            }
            if (treatmentPastAdmin != null) {
                recordTransition(
                    hive = hive,
                    fsmType = "OPERATIONAL_LIFECYCLE",
                    fromState = hive.operationalState.name,
                    toState = OperationalState.WITHDRAWAL.name,
                    event = event,
                    reason = "Treatment administration ended for ${treatmentPastAdmin.medicineName}; entering withdrawal until ${treatmentPastAdmin.withdrawalEndDate}"
                )
                hive.operationalState = OperationalState.WITHDRAWAL
                hiveRepository.save(hive)
                return
            }
        }

        // Rule B: withdrawal cleared when no active treatments remain
        if (hive.operationalState == OperationalState.WITHDRAWAL && activeTreatments.isEmpty()) {
            recordTransition(
                hive = hive,
                fsmType = "OPERATIONAL_LIFECYCLE",
                fromState = hive.operationalState.name,
                toState = OperationalState.PREPARATION.name,
                event = event,
                reason = "Withdrawal period completed; sanitary clearance restored"
            )
            hive.operationalState = OperationalState.PREPARATION
            hiveRepository.save(hive)
        }
    }

    private fun recordTransition(
        hive: Hive,
        fsmType: String,
        fromState: String,
        toState: String,
        event: ApiaryDomainEvent,
        reason: String
    ) {
        transitionRepository.save(
            HiveStateTransition(
                hive = hive,
                fsmType = fsmType,
                fromState = fromState,
                toState = toState,
                triggerEvent = "${event::class.simpleName}:${event.eventId}",
                reason = reason,
                transitionTimestamp = Instant.now()
            )
        )
    }
}
