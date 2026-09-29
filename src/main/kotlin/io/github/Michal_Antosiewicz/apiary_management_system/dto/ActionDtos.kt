package io.github.Michal_Antosiewicz.apiarymanagementsystem.dto

import java.time.Instant
import java.util.UUID

data class ApplyTreatmentRequest(
    val hiveId: UUID,
    val medicineName: String,
    val activeSubstance: String,
    val withdrawalDays: Int,
    val durationDays: Int = 14
)

data class TreatmentResponse(
    val treatmentId: UUID,
    val hiveId: UUID,
    val medicineName: String,
    val startDate: Instant,
    val withdrawalEndDate: Instant,
    val hiveOperationalState: String
)

data class HarvestHoneyRequest(
    val targetSuperCount: Int = 1,
    val operatorNotes: String? = null
)

data class HarvestHoneyResponse(
    val hiveId: UUID,
    val status: String,
    val message: String
)

data class ErrorResponse(
    val timestamp: Instant = Instant.now(),
    val status: Int,
    val error: String,
    val message: String,
    val details: Map<String, Any>? = null
)