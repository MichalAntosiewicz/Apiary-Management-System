package io.github.Michal_Antosiewicz.apiary_management_system.event

import io.github.Michal_Antosiewicz.apiary_management_system.domain.BroodPattern
import io.github.Michal_Antosiewicz.apiary_management_system.domain.CellPlacement
import io.github.Michal_Antosiewicz.apiary_management_system.domain.CellStage
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

sealed interface ApiaryDomainEvent {
    val eventId: UUID
    val hiveId: UUID
    val occurredAt: Instant
}

data class InspectionRecordedEvent(
    override val eventId: UUID = UUID.randomUUID(),
    override val hiveId: UUID,
    override val occurredAt: Instant = Instant.now(),
    val inspectionId: UUID,
    val framesOfBees: Int,
    val framesOfBrood: Int,
    val honeyStoresKg: BigDecimal,
    val freeCombSpacePercent: BigDecimal,
    val queenSeen: Boolean,
    val broodPattern: BroodPattern,
    val queenCellsCount: Int,
    val queenCellsPlacement: CellPlacement,
    val queenCellsStage: CellStage,
    val varroaMiteCount: Int,
    val signsOfPests: Boolean,
    val temperamentScore: Int?
) : ApiaryDomainEvent

data class TreatmentAppliedEvent(
    override val eventId: UUID = UUID.randomUUID(),
    override val hiveId: UUID,
    override val occurredAt: Instant = Instant.now(),
    val treatmentId: UUID,
    val medicineName: String,
    val activeSubstance: String,
    val startDate: Instant,
    val treatmentEndDate: Instant,
    val withdrawalEndDate: Instant
) : ApiaryDomainEvent

data class HoneyHarvestAttemptedEvent(
    override val eventId: UUID = UUID.randomUUID(),
    override val hiveId: UUID,
    override val occurredAt: Instant = Instant.now(),
    val requestedByUserId: String? = null,
    val targetSuperCount: Int = 1
) : ApiaryDomainEvent

data class SwarmInterventionAppliedEvent(
    override val eventId: UUID = UUID.randomUUID(),
    override val hiveId: UUID,
    override val occurredAt: Instant = Instant.now(),
    val interventionType: String,
    val queenCellsDestroyed: Boolean,
    val artificialSwarmCreated: Boolean
) : ApiaryDomainEvent

data class QueenReplacedEvent(
    override val eventId: UUID = UUID.randomUUID(),
    override val hiveId: UUID,
    override val occurredAt: Instant = Instant.now(),
    val newQueenId: UUID,
    val emergenceYear: Int,
    val breedLine: String,
    val markingColor: String?
) : ApiaryDomainEvent