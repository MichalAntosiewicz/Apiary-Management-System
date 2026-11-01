package io.github.Michal_Antosiewicz.apiary_management_system.service

import io.github.Michal_Antosiewicz.apiary_management_system.domain.*
import io.github.Michal_Antosiewicz.apiary_management_system.event.InspectionRecordedEvent
import io.github.Michal_Antosiewicz.apiary_management_system.repository.DecisionAlertRepository
import io.github.Michal_Antosiewicz.apiary_management_system.repository.HiveRepository
import io.github.Michal_Antosiewicz.apiary_management_system.repository.QueenRepository
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.ZoneOffset

@Service
class DecisionSupportEngine(
    private val hiveRepository: HiveRepository,
    private val queenRepository: QueenRepository,
    private val alertRepository: DecisionAlertRepository
) {

    @EventListener
    @Transactional
    fun handleInspection(event: InspectionRecordedEvent) {
        val hive = hiveRepository.findById(event.hiveId).orElse(null) ?: return
        val activeQueen = queenRepository.findByHiveIdAndIsActiveTrue(event.hiveId).orElse(null)

        val currentYear = event.occurredAt.atZone(ZoneOffset.UTC).year
        val queenAgeYears = activeQueen?.let { currentYear - it.emergenceYear } ?: 0

        // 1. Evaluate Differential Diagnosis
        evaluateSwarmingRisk(hive, event, queenAgeYears)?.let { alertRepository.save(it) }
        evaluateSupersedure(hive, event, queenAgeYears)?.let { alertRepository.save(it) }
        evaluateAbscondingRisk(hive, event)?.let { alertRepository.save(it) }
    }

    private fun evaluateSwarmingRisk(
        hive: Hive,
        event: InspectionRecordedEvent,
        queenAgeYears: Int
    ): DecisionAlert? {
        var score = 0.0

        // Indicator 1: Queen cells placement and quantity
        if (event.queenCellsPlacement == CellPlacement.MARGINAL) {
            when {
                event.queenCellsCount >= 6 -> score += 0.40
                event.queenCellsCount in 1..5 -> score += 0.25
            }
            if (event.queenCellsStage == CellStage.SEALED) {
                score += 0.20
            } else if (event.queenCellsStage == CellStage.OPEN_WITH_LARVA) {
                score += 0.10
            }
        }

        // Indicator 2: Brood chamber congestion and comb space deficit
        if (event.freeCombSpacePercent <= BigDecimal("10.0")) {
            score += 0.25
        } else if (event.freeCombSpacePercent <= BigDecimal("20.0")) {
            score += 0.15
        }

        if (event.framesOfBees >= 10 && event.framesOfBrood >= 6) {
            score += 0.10
        }

        // Indicator 3: Queen age / pheromone decline (QMP)
        if (queenAgeYears >= 2) {
            score += 0.10
        }

        val riskScore = BigDecimal(score.coerceIn(0.0, 1.0)).setScale(3, RoundingMode.HALF_UP)

        return when {
            riskScore >= BigDecimal("0.750") -> DecisionAlert(
                hive = hive,
                eventId = event.eventId,
                createdAt = event.occurredAt,
                alertType = AlertType.REPRODUCTIVE_SWARM,
                severity = AlertSeverity.CRITICAL,
                riskScore = riskScore,
                recommendationText = "Swarming indicators exceed the high heuristic threshold ($riskScore). Review queen cells and available comb space with an experienced beekeeper before choosing an intervention."
            )
            riskScore >= BigDecimal("0.450") -> DecisionAlert(
                hive = hive,
                eventId = event.eventId,
                createdAt = event.occurredAt,
                alertType = AlertType.REPRODUCTIVE_SWARM,
                severity = AlertSeverity.WARNING,
                riskScore = riskScore,
                recommendationText = "Swarming indicators exceed the advisory threshold ($riskScore). Review congestion, queen cells and local conditions before changing comb space."
            )
            else -> null
        }
    }

    private fun evaluateSupersedure(
        hive: Hive,
        event: InspectionRecordedEvent,
        queenAgeYears: Int
    ): DecisionAlert? {
        var score = 0.0

        // Indicator 1: Central placement with low cell count
        if (event.queenCellsPlacement == CellPlacement.CENTRAL && event.queenCellsCount in 1..4) {
            score += 0.50
        }

        // Indicator 2: Queen performance and brood pattern
        when (event.broodPattern) {
            BroodPattern.SPOTTY -> score += 0.30
            BroodPattern.DRONE_BROOD_ONLY -> score += 0.40
            BroodPattern.ABSENT -> score += 0.20
            BroodPattern.SOLID_COMPACT -> score -= 0.20
        }

        // Indicator 3: Queen aging
        if (queenAgeYears >= 2) {
            score += 0.15
        }

        val riskScore = BigDecimal(score.coerceIn(0.0, 1.0)).setScale(3, RoundingMode.HALF_UP)

        return if (riskScore >= BigDecimal("0.550")) {
            DecisionAlert(
                hive = hive,
                eventId = event.eventId,
                createdAt = event.occurredAt,
                alertType = AlertType.SUPERSEDURE,
                severity = AlertSeverity.INFO,
                riskScore = riskScore,
                recommendationText = "Inspection indicators are consistent with possible supersedure ($riskScore). Review queen status and brood pattern with an experienced beekeeper before intervening."
            )
        } else null
    }

    private fun evaluateAbscondingRisk(
        hive: Hive,
        event: InspectionRecordedEvent
    ): DecisionAlert? {
        var score = 0.0

        // Indicator 1: Heavy Varroa destructor infestation
        when {
            event.varroaMiteCount >= 25 -> score += 0.35
            event.varroaMiteCount >= 12 -> score += 0.20
        }

        // Indicator 2: Acute nutritional deficit (Starvation risk)
        if (event.honeyStoresKg < BigDecimal("1.50")) {
            score += 0.40
        } else if (event.honeyStoresKg < BigDecimal("2.50")) {
            score += 0.20
        }

        // Indicator 3: Stressors and pest intrusions (wax moth, wasps, ants)
        if (event.signsOfPests) {
            score += 0.25
        }

        // Synergy: concurrent mite pressure and nutritional dearth
        if (event.varroaMiteCount >= 12 && event.honeyStoresKg < BigDecimal("2.50")) {
            score += 0.20
        }

        val riskScore = BigDecimal(score.coerceIn(0.0, 1.0)).setScale(3, RoundingMode.HALF_UP)

        return if (riskScore >= BigDecimal("0.550")) {
            val severity = if (riskScore >= BigDecimal("0.750")) AlertSeverity.CRITICAL else AlertSeverity.WARNING
            DecisionAlert(
                hive = hive,
                eventId = event.eventId,
                createdAt = event.occurredAt,
                alertType = AlertType.COLONY_ABSCONDING,
                severity = severity,
                riskScore = riskScore,
                recommendationText = "Recorded stress indicators exceed the advisory threshold ($riskScore). Check measurement context, food stores and pests; seek qualified advice on management where needed."
            )
        } else null
    }
}