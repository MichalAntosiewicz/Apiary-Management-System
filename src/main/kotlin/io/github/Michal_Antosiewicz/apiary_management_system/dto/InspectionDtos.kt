package io.github.Michal_Antosiewicz.apiary_management_system.dto

import io.github.Michal_Antosiewicz.apiary_management_system.domain.AlertSeverity
import io.github.Michal_Antosiewicz.apiary_management_system.domain.AlertType
import io.github.Michal_Antosiewicz.apiary_management_system.domain.BroodPattern
import io.github.Michal_Antosiewicz.apiary_management_system.domain.CellPlacement
import io.github.Michal_Antosiewicz.apiary_management_system.domain.CellStage
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import jakarta.validation.constraints.*

data class CreateInspectionRequest(
    val hiveId: UUID,
    @field:Min(0) val framesOfBees: Int,
    @field:Min(0) val framesOfBrood: Int,
    @field:DecimalMin("0.0") @field:Digits(integer = 3, fraction = 2) val honeyStoresKg: BigDecimal,
    @field:DecimalMin("0.0") @field:DecimalMax("100.0") @field:Digits(integer = 3, fraction = 2) val freeCombSpacePercent: BigDecimal,
    val queenSeen: Boolean = false,
    val broodPattern: BroodPattern,
    @field:Min(0) val queenCellsCount: Int = 0,
    val queenCellsPlacement: CellPlacement = CellPlacement.NONE,
    val queenCellsStage: CellStage = CellStage.NONE,
    @field:Min(0) val varroaMiteCount: Int = 0,
    val signsOfPests: Boolean = false,
    @field:Min(1) @field:Max(5) val temperamentScore: Int? = null,
    @field:Size(max = 4000) val notes: String? = null
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