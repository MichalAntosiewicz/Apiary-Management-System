package io.github.Michal_Antosiewicz.apiary_management_system.dto

import java.time.Instant
import java.util.UUID
import jakarta.validation.constraints.*

data class ApplyTreatmentRequest(
    val hiveId: UUID? = null,
    @field:NotBlank @field:Size(max = 100) val medicineName: String,
    @field:NotBlank @field:Size(max = 100) val activeSubstance: String,
    @field:Min(0) @field:Max(3650) val withdrawalDays: Int,
    @field:Min(1) @field:Max(3650) val durationDays: Int = 14
)

data class TreatmentResponse(
    val treatmentId: UUID,
    val hiveId: UUID,
    val medicineName: String,
    val startDate: Instant,
    val treatmentEndDate: Instant,
    val withdrawalEndDate: Instant,
    val hiveOperationalState: String
)

data class HarvestHoneyRequest(
    @field:Min(1) @field:Max(1000) val targetSuperCount: Int = 1,
    @field:Size(max = 4000) val operatorNotes: String? = null
)

data class HarvestHoneyResponse(
    val hiveId: UUID,
    val status: String,
    val message: String,
    val eventId: UUID,
    val reasonCode: String,
    val blockedUntil: Instant? = null
)

data class ErrorResponse(
    val timestamp: Instant = Instant.now(),
    val status: Int,
    val error: String,
    val message: String,
    val details: Map<String, Any>? = null
)