package io.github.Michal_Antosiewicz.apiary_management_system.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.Michal_Antosiewicz.apiary_management_system.domain.DomainEventEntry
import io.github.Michal_Antosiewicz.apiary_management_system.domain.Inspection
import io.github.Michal_Antosiewicz.apiary_management_system.dto.CreateInspectionRequest
import io.github.Michal_Antosiewicz.apiary_management_system.dto.InspectionAlertDto
import io.github.Michal_Antosiewicz.apiary_management_system.dto.InspectionResponse
import io.github.Michal_Antosiewicz.apiary_management_system.event.InspectionRecordedEvent
import io.github.Michal_Antosiewicz.apiary_management_system.repository.DecisionAlertRepository
import io.github.Michal_Antosiewicz.apiary_management_system.repository.DomainEventEntryRepository
import io.github.Michal_Antosiewicz.apiary_management_system.repository.HiveRepository
import io.github.Michal_Antosiewicz.apiary_management_system.repository.InspectionRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.Clock
import java.time.temporal.ChronoUnit
import jakarta.validation.Validator
import io.github.Michal_Antosiewicz.apiary_management_system.domain.OperationalState
import java.util.UUID

@Service
class InspectionService(
    private val hiveRepository: HiveRepository,
    private val inspectionRepository: InspectionRepository,
    private val domainEventRepository: DomainEventEntryRepository,
    private val alertRepository: DecisionAlertRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val objectMapper: ObjectMapper,
    private val validator: Validator,
    private val clock: Clock
) {

    @Transactional
    fun recordInspection(request: CreateInspectionRequest): InspectionResponse {
        val errors = validator.validate(request)
        require(errors.isEmpty()) { errors.joinToString("; ") { "${it.propertyPath}: ${it.message}" } }
        val hive = hiveRepository.findByIdForUpdate(request.hiveId)
            .orElseThrow { IllegalArgumentException("Hive not found with ID: ${request.hiveId}") }

        require(hive.operationalState != OperationalState.DECOMMISSIONED) { "Cannot inspect a decommissioned hive" }
        val inspection = inspectionRepository.save(
            Inspection(
                id = UUID.randomUUID(),
                hive = hive,
                inspectionDate = Instant.now(clock).truncatedTo(ChronoUnit.MICROS),
                framesOfBees = request.framesOfBees,
                framesOfBrood = request.framesOfBrood,
                honeyStoresKg = request.honeyStoresKg,
                freeCombSpacePercent = request.freeCombSpacePercent,
                queenSeen = request.queenSeen,
                broodPattern = request.broodPattern,
                queenCellsCount = request.queenCellsCount,
                queenCellsPlacement = request.queenCellsPlacement,
                queenCellsStage = request.queenCellsStage,
                varroaMiteCount = request.varroaMiteCount,
                signsOfPests = request.signsOfPests,
                temperamentScore = request.temperamentScore,
                notes = request.notes
            )
        )

        val domainEvent = InspectionRecordedEvent(
            eventId = UUID.randomUUID(),
            hiveId = hive.id,
            occurredAt = inspection.inspectionDate,
            inspectionId = inspection.id,
            framesOfBees = inspection.framesOfBees,
            framesOfBrood = inspection.framesOfBrood,
            honeyStoresKg = inspection.honeyStoresKg,
            freeCombSpacePercent = inspection.freeCombSpacePercent,
            queenSeen = inspection.queenSeen,
            broodPattern = inspection.broodPattern,
            queenCellsCount = inspection.queenCellsCount,
            queenCellsPlacement = inspection.queenCellsPlacement,
            queenCellsStage = inspection.queenCellsStage,
            varroaMiteCount = inspection.varroaMiteCount,
            signsOfPests = inspection.signsOfPests,
            temperamentScore = inspection.temperamentScore
        )

        // Persist event JSON before synchronous consumers create foreign-key references
        val payloadJson = objectMapper.writeValueAsString(domainEvent)
        domainEventRepository.saveAndFlush(
            DomainEventEntry(
                id = domainEvent.eventId,
                hive = hive,
                eventType = domainEvent::class.simpleName ?: "InspectionRecordedEvent",
                payload = payloadJson,
                occurredAt = domainEvent.occurredAt
            )
        )

        // Publish event to invoke FSM transitions and DSS rule engine listeners
        eventPublisher.publishEvent(domainEvent)

        // Return only alerts linked to this inspection event; historical active alerts have a separate endpoint
        val activeAlerts = alertRepository.findByEventId(domainEvent.eventId)
            .map { alert ->
                InspectionAlertDto(
                    alertType = alert.alertType,
                    severity = alert.severity,
                    riskScore = alert.riskScore,
                    recommendationText = alert.recommendationText
                )
            }

        return InspectionResponse(
            inspectionId = inspection.id,
            hiveId = hive.id,
            inspectionDate = inspection.inspectionDate,
            currentBiologicalState = hive.biologicalState.name,
            alertsGenerated = activeAlerts
        )
    }
}
