package io.github.Michal_Antosiewicz.apiary_management_system.service

import io.github.Michal_Antosiewicz.apiary_management_system.domain.*
import io.github.Michal_Antosiewicz.apiary_management_system.event.*
import io.github.Michal_Antosiewicz.apiary_management_system.repository.DecisionAlertRepository
import io.github.Michal_Antosiewicz.apiary_management_system.repository.HiveRepository
import io.github.Michal_Antosiewicz.apiary_management_system.repository.HiveStateTransitionRepository
import io.github.Michal_Antosiewicz.apiary_management_system.repository.TreatmentRepository
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import io.github.Michal_Antosiewicz.apiary_management_system.repository.HarvestDecisionRepository

@Service
class HiveStateMachineService(
    private val hiveRepository: HiveRepository,
    private val treatmentRepository: TreatmentRepository,
    private val transitionRepository: HiveStateTransitionRepository,
    private val alertRepository: DecisionAlertRepository,
    private val decisionRepository: HarvestDecisionRepository
) {

    // Handles biological and operational state transitions triggered by field inspection data
    @EventListener
    @Transactional
    fun onInspectionRecorded(event: InspectionRecordedEvent) {
        val hive = hiveRepository.findById(event.hiveId).orElse(null) ?: return
        require(hive.operationalState != OperationalState.DECOMMISSIONED) { "Cannot inspect a decommissioned hive" }
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
        require(hive.operationalState != OperationalState.DECOMMISSIONED) { "Cannot treat a decommissioned hive" }
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

    // A business rejection is a persisted result, never an exception that rolls back its own audit.
    @EventListener
    @Order(0)
    @Transactional
    fun onHoneyHarvestAttempted(event: HoneyHarvestAttemptedEvent) {
        if (decisionRepository.existsById(event.eventId)) return
        val hive = hiveRepository.findById(event.hiveId).orElseThrow { IllegalArgumentException("Hive not found") }
        val unavailable = hive.operationalState in setOf(OperationalState.DECOMMISSIONED, OperationalState.WINTERING)
        val active = treatmentRepository.findActiveTreatmentsByHiveId(hive.id, event.occurredAt)
        val conflict = active.maxByOrNull { it.withdrawalEndDate }
        val approved = !unavailable && conflict == null
        val reason = when {
            unavailable -> "OPERATIONAL_STATE_BLOCKED"
            conflict != null -> "ACTIVE_WITHDRAWAL"
            else -> "SANITARY_GUARD_CLEARED"
        }
        val message = when {
            unavailable -> "Harvest rejected in operational state ${hive.operationalState}"
            conflict != null -> "Harvest rejected: ${conflict.medicineName} restriction lasts until ${conflict.withdrawalEndDate}"
            else -> "No recorded treatment restriction is active; harvest permitted"
        }
        if (conflict != null && !unavailable) {
            alertRepository.save(DecisionAlert(hive = hive, alertType = AlertType.SANITARY_VIOLATION,
                severity = AlertSeverity.CRITICAL, riskScore = BigDecimal("1.000"),
                recommendationText = message, eventId = event.eventId, createdAt = event.occurredAt))
        }
        val target = if (approved) OperationalState.ACTIVE_HONEY_FLOW else hive.operationalState
        recordTransition(hive, "OPERATIONAL_LIFECYCLE", hive.operationalState.name, target.name,
            event, "${if (approved) "APPROVED" else "REJECTED"}: $reason; $message")
        if (hive.operationalState != target) {
            hive.operationalState = target
            hiveRepository.save(hive)
        }
        decisionRepository.save(HarvestDecision(event.eventId, hive, approved, reason, message,
            event.occurredAt, if (!unavailable) conflict?.withdrawalEndDate else null))
    }

    // Resolves swarm preparation state following successful beekeeper intervention
    @EventListener
    @Transactional
    fun onSwarmInterventionApplied(event: SwarmInterventionAppliedEvent) {
        val hive = hiveRepository.findById(event.hiveId).orElse(null) ?: return

        if (hive.operationalState != OperationalState.DECOMMISSIONED &&
            hive.biologicalState == BiologicalState.SWARM_PREPARATION &&
            (event.artificialSwarmCreated || event.queenCellsDestroyed)) {
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

        if (hive.operationalState != OperationalState.DECOMMISSIONED && hive.biologicalState == BiologicalState.SUPERSEDURE_ACTIVE) {
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
        if (hive.operationalState == OperationalState.DECOMMISSIONED) return
        val active = treatmentRepository.findActiveTreatmentsByHiveId(hive.id, event.occurredAt)
        val target = when {
            active.any { event.occurredAt.isBefore(it.treatmentEndDate) } -> OperationalState.TREATMENT_IN_PROGRESS
            active.isNotEmpty() -> OperationalState.WITHDRAWAL
            hive.operationalState in setOf(OperationalState.TREATMENT_IN_PROGRESS, OperationalState.WITHDRAWAL) -> OperationalState.PREPARATION
            else -> hive.operationalState
        }
        if (target != hive.operationalState) {
            recordTransition(hive, "OPERATIONAL_LIFECYCLE", hive.operationalState.name, target.name,
                event, "Treatment status reconciled at event time ${event.occurredAt}")
            hive.operationalState = target
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
                transitionTimestamp = event.occurredAt,
                eventId = event.eventId
            )
        )
    }
}
