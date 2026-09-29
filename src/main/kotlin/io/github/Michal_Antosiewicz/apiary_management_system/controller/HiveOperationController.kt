package io.github.Michal_Antosiewicz.apiary_management_system.controller

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.Michal_Antosiewicz.apiary_management_system.domain.DecisionAlert
import io.github.Michal_Antosiewicz.apiary_management_system.domain.DomainEventEntry
import io.github.Michal_Antosiewicz.apiary_management_system.domain.Treatment
import io.github.Michal_Antosiewicz.apiary_management_system.dto.*
import io.github.Michal_Antosiewicz.apiary_management_system.event.HoneyHarvestAttemptedEvent
import io.github.Michal_Antosiewicz.apiary_management_system.event.TreatmentAppliedEvent
import io.github.Michal_Antosiewicz.apiary_management_system.repository.DecisionAlertRepository
import io.github.Michal_Antosiewicz.apiary_management_system.repository.DomainEventEntryRepository
import io.github.Michal_Antosiewicz.apiary_management_system.repository.HiveRepository
import io.github.Michal_Antosiewicz.apiary_management_system.repository.TreatmentRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@RestController
@RequestMapping("/api/v1/hives")
class HiveOperationController(
    private val hiveRepository: HiveRepository,
    private val treatmentRepository: TreatmentRepository,
    private val alertRepository: DecisionAlertRepository,
    private val domainEventRepository: DomainEventEntryRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val objectMapper: ObjectMapper
) {

    @PostMapping("/{hiveId}/harvest")
    @ResponseStatus(HttpStatus.OK)
    @Transactional
    fun attemptHoneyHarvest(
        @PathVariable hiveId: UUID,
        @RequestBody(required = false) request: HarvestHoneyRequest?
    ): HarvestHoneyResponse {
        val hive = hiveRepository.findById(hiveId)
            .orElseThrow { IllegalArgumentException("Hive not found with ID: $hiveId") }

        val harvestEvent = HoneyHarvestAttemptedEvent(
            hiveId = hive.id,
            targetSuperCount = request?.targetSuperCount ?: 1
        )

        // Persist immutable domain event log
        val payloadJson = objectMapper.writeValueAsString(harvestEvent)
        domainEventRepository.save(
            DomainEventEntry(
                id = harvestEvent.eventId,
                hive = hive,
                eventType = harvestEvent::class.simpleName ?: "HoneyHarvestAttemptedEvent",
                payload = payloadJson,
                occurredAt = harvestEvent.occurredAt
            )
        )

        // Triggers FSM guard verification (will throw SanitaryViolationException if under quarantine)
        eventPublisher.publishEvent(harvestEvent)

        return HarvestHoneyResponse(
            hiveId = hive.id,
            status = "APPROVED",
            message = "Sanitary guard cleared. Honey harvest operation permitted."
        )
    }

    @PostMapping("/{hiveId}/treatments")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    fun applyTreatment(
        @PathVariable hiveId: UUID,
        @RequestBody request: ApplyTreatmentRequest
    ): TreatmentResponse {
        val hive = hiveRepository.findById(hiveId)
            .orElseThrow { IllegalArgumentException("Hive not found with ID: $hiveId") }

        val now = Instant.now()
        val treatmentEndDate = now.plus(request.durationDays.toLong(), ChronoUnit.DAYS)
        val withdrawalEndDate = treatmentEndDate.plus(request.withdrawalDays.toLong(), ChronoUnit.DAYS)

        val treatment = treatmentRepository.save(
            Treatment(
                id = UUID.randomUUID(),
                hive = hive,
                medicineName = request.medicineName,
                activeSubstance = request.activeSubstance,
                startDate = now,
                endDate = treatmentEndDate,
                withdrawalDays = request.withdrawalDays,
                withdrawalEndDate = withdrawalEndDate
            )
        )

        val treatmentEvent = TreatmentAppliedEvent(
            treatmentId = treatment.id,
            hiveId = hive.id,
            medicineName = treatment.medicineName,
            activeSubstance = treatment.activeSubstance,
            startDate = treatment.startDate,
            withdrawalDays = treatment.withdrawalDays,
            withdrawalEndDate = treatment.withdrawalEndDate
        )

        val payloadJson = objectMapper.writeValueAsString(treatmentEvent)
        domainEventRepository.save(
            DomainEventEntry(
                id = treatmentEvent.eventId,
                hive = hive,
                eventType = treatmentEvent::class.simpleName ?: "TreatmentAppliedEvent",
                payload = payloadJson,
                occurredAt = treatmentEvent.occurredAt
            )
        )

        eventPublisher.publishEvent(treatmentEvent)

        return TreatmentResponse(
            treatmentId = treatment.id,
            hiveId = hive.id,
            medicineName = treatment.medicineName,
            startDate = treatment.startDate,
            withdrawalEndDate = treatment.withdrawalEndDate,
            hiveOperationalState = hive.operationalState.name
        )
    }

    @GetMapping("/{hiveId}/alerts")
    fun getActiveAlerts(@PathVariable hiveId: UUID): List<InspectionAlertDto> {
        return alertRepository.findByHiveIdAndIsAcknowledgedFalseOrderByCreatedAtDesc(hiveId)
            .map {
                InspectionAlertDto(
                    alertType = it.alertType,
                    severity = it.severity,
                    riskScore = it.riskScore,
                    recommendationText = it.recommendationText
                )
            }
    }
}