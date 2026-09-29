package io.github.Michal_Antosiewicz.apiary_management_system.domain

import java.time.Instant
import java.util.UUID

class SanitaryViolationException(
    val hiveId: UUID,
    val medicineName: String,
    val withdrawalEndDate: Instant,
    message: String = "Honey harvest operation rejected: Hive $hiveId is under quarantine for $medicineName until $withdrawalEndDate"
) : IllegalStateException(message)