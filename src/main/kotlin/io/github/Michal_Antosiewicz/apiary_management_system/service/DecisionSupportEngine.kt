package io.github.Michal_Antosiewicz.apiarymanagementsystem.service

import io.github.Michal_Antosiewicz.apiarymanagementsystem.domain.*
import io.github.Michal_Antosiewicz.apiarymanagementsystem.event.InspectionRecordedEvent
import io.github.Michal_Antosiewicz.apiarymanagementsystem.repository.DecisionAlertRepository
import io.github.Michal_Antosiewicz.apiarymanagementsystem.repository.HiveRepository
import io.github.Michal_Antosiewicz.apiarymanagementsystem.repository.QueenRepository
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Year

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

        val currentYear = Year.now().value
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
                alertType = AlertType.REPRODUCTIVE_SWARM,
                severity = AlertSeverity.CRITICAL,
                riskScore = riskScore,
                recommendationText = "Critical swarming threshold exceeded ($riskScore). Marginal queen cells and severe congestion detected. Immediate artificial swarm, split, or comb volume expansion required."
            )
            riskScore >= BigDecimal("0.450") -> DecisionAlert(
                hive = hive,
                alertType = AlertType.REPRODUCTIVE_SWARM,
                severity = AlertSeverity.WARNING,
                riskScore = riskScore,
                recommendationText = "Moderate swarming preparation detected ($riskScore). Brood area congestion rising. Provide empty wax foundation frames to stimulate comb building."
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
                alertType = AlertType.SUPERSEDURE,
                severity = AlertSeverity.INFO,
                riskScore = riskScore,
                recommendationText = "Active natural supersedure detected ($riskScore). Do NOT destroy queen cells. The colony is replacing a failing or depleted queen."
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
            event.varroaMiteCount >= 25 -> score += 0.50
            event.varroaMiteCount >= 10 -> score += 0.25
        }

        // Indicator 2: Acute nutritional deficit (Starvation risk)
        if (event.honeyStoresKg < BigDecimal("1.50")) {
            score += 0.35
        } else if (event.honeyStoresKg < BigDecimal("3.00")) {
            score += 0.15
        }

        // Indicator 3: Stressors and pest intrusions (wax moth, wasps, ants)
        if (event.signsOfPests) {
            score += 0.25
        }

        val riskScore = BigDecimal(score.coerceIn(0.0, 1.0)).setScale(3, RoundingMode.HALF_UP)

        return if (riskScore >= BigDecimal("0.500")) {
            val severity = if (riskScore >= BigDecimal("0.750")) AlertSeverity.CRITICAL else AlertSeverity.WARNING
            DecisionAlert(
                hive = hive,
                alertType = AlertType.COLONY_ABSCONDING,
                severity = severity,
                riskScore = riskScore,
                recommendationText = "High absconding risk ($riskScore). Stress factors detected (parasite load, severe resource dearth, or pest disturbance). Immediate veterinary intervention or supplemental feeding necessary."
            )
        } else null
    }
}