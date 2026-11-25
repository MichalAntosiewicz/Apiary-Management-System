package io.github.Michal_Antosiewicz.apiary_management_system.service
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.Michal_Antosiewicz.apiary_management_system.domain.*
import io.github.Michal_Antosiewicz.apiary_management_system.dto.*
import io.github.Michal_Antosiewicz.apiary_management_system.event.*
import io.github.Michal_Antosiewicz.apiary_management_system.repository.*
import jakarta.validation.Validator
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class HiveOperationService(private val hives: HiveRepository, private val treatments: TreatmentRepository,
    private val events: DomainEventEntryRepository, private val decisions: HarvestDecisionRepository,
    private val publisher: ApplicationEventPublisher, private val mapper: ObjectMapper,
    private val validator: Validator, private val clock: Clock) {
    private fun validate(request: Any) {
        val errors = validator.validate(request)
        require(errors.isEmpty()) { errors.joinToString("; ") { "${it.propertyPath}: ${it.message}" } }
    }
    @Transactional
    fun attemptHoneyHarvest(hiveId: UUID, request: HarvestHoneyRequest): HarvestHoneyResponse {
        validate(request)
        val hive = hives.findByIdForUpdate(hiveId).orElseThrow { IllegalArgumentException("Hive not found: $hiveId") }
        // Timestamp after obtaining the per-hive lock. Clients cannot backdate guard checks.
        val event = HoneyHarvestAttemptedEvent(hiveId = hiveId, occurredAt = Instant.now(clock).truncatedTo(ChronoUnit.MICROS),
            targetSuperCount = request.targetSuperCount, operatorNotes = request.operatorNotes)
        persistAndPublish(hive, event)
        val decision = decisions.findById(event.eventId).orElseThrow { IllegalStateException("Harvest listener did not record a decision") }
        return HarvestHoneyResponse(hive.id, if (decision.approved) "APPROVED" else "REJECTED",
            decision.message, decision.eventId, decision.reasonCode, decision.blockedUntil)
    }
    @Transactional
    fun applyTreatment(hiveId: UUID, request: ApplyTreatmentRequest): TreatmentResponse {
        validate(request)
        require(request.hiveId == null || request.hiveId == hiveId) { "hiveId in body must match the URL" }
        val hive = hives.findByIdForUpdate(hiveId).orElseThrow { IllegalArgumentException("Hive not found: $hiveId") }
        require(hive.operationalState != OperationalState.DECOMMISSIONED) { "Cannot treat a decommissioned hive" }
        val now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS)
        val treatment = treatments.saveAndFlush(Treatment(hive = hive, medicineName = request.medicineName,
            activeSubstance = request.activeSubstance, startDate = now,
            treatmentEndDate = now.plus(request.durationDays.toLong(), ChronoUnit.DAYS),
            withdrawalDays = request.withdrawalDays,
            withdrawalEndDate = now.plus(request.durationDays.toLong() + request.withdrawalDays, ChronoUnit.DAYS)))
        val event = TreatmentAppliedEvent(hiveId = hive.id, occurredAt = now, treatmentId = treatment.id,
            medicineName = treatment.medicineName, activeSubstance = treatment.activeSubstance,
            startDate = now, treatmentEndDate = treatment.treatmentEndDate, withdrawalEndDate = treatment.withdrawalEndDate)
        persistAndPublish(hive, event)
        return TreatmentResponse(treatment.id, hive.id, treatment.medicineName, treatment.startDate,
            treatment.treatmentEndDate, treatment.withdrawalEndDate, hive.operationalState.name)
    }
    private fun persistAndPublish(hive: Hive, event: ApiaryDomainEvent) {
        events.saveAndFlush(DomainEventEntry(id = event.eventId, hive = hive,
            eventType = event::class.simpleName!!, payload = mapper.writeValueAsString(event), occurredAt = event.occurredAt))
        publisher.publishEvent(event)
    }
}
