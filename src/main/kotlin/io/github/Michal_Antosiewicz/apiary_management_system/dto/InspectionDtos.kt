package io.github.Michal_Antosiewicz.apiary_management_system.dto

import io.github.Michal_Antosiewicz.apiary_management_system.domain.AlertSeverity
import io.github.Michal_Antosiewicz.apiary_management_system.domain.AlertType
import io.github.Michal_Antosiewicz.apiary_management_system.domain.BroodPattern
import io.github.Michal_Antosiewicz.apiary_management_system.domain.CellPlacement
import io.github.Michal_Antosiewicz.apiary_management_system.domain.CellStage
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class CreateInspectionRequest(
    val hiveId: UUID,
    val framesOfBees: Int,
    val framesOfBrood: Int,
    val honeyStoresKg: BigDecimal,
    val freeCombSpacePercent: BigDecimal,
    val queenSeen: Boolean = false,
    val broodPattern: BroodPattern,
    val queenCellsCount: Int = 0,
    val queenCellsPlacement: CellPlacement = CellPlacement.NONE,
    val queenCellsStage: CellStage = CellStage.NONE,
    val varroaMiteCount: Int = 0,
    val signsOfPests: Boolean = false,
    val temperamentScore: Int? = null,
    val notes: String? = null
)

data class InspectionResponse(
    val inspectionId: UUID,
    val hiveId: UUID,
    val inspectionDate: Instant,
    val currentBiologicalState: String,
    val alertsGenerated: List<InspectionAlertDto>
)

data class InspectionAlertDto(
    val alertType: AlertType,
    val severity: AlertSeverity,
    val riskScore: BigDecimal,
    val recommendationText: String
)