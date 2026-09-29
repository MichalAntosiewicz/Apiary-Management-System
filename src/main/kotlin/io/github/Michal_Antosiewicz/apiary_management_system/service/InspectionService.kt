package io.github.Michal_Antosiewicz.apiarymanagementsystem.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.Michal_Antosiewicz.apiarymanagementsystem.domain.DomainEventEntry
import io.github.Michal_Antosiewicz.apiarymanagementsystem.domain.Inspection
import io.github.Michal_Antosiewicz.apiarymanagementsystem.dto.CreateInspectionRequest
import io.github.Michal_Antosiewicz.apiarymanagementsystem.dto.InspectionAlertDto
import io.github.Michal_Antosiewicz.apiarymanagementsystem.dto.InspectionResponse
import io.github.Michal_Antosiewicz.apiarymanagementsystem.event.InspectionRecordedEvent
import io.github.Michal_Antosiewicz.apiarymanagementsystem.repository.DecisionAlertRepository
import io.github.Michal_Antosiewicz.apiarymanagementsystem.repository.DomainEventEntryRepository
import io.github.Michal_Antosiewicz.apiarymanagementsystem.repository.HiveRepository
import io.github.Michal_Antosiewicz.apiarymanagementsystem.repository.InspectionRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class InspectionService(
    private val hiveRepository: HiveRepository,
    private val inspectionRepository: InspectionRepository,
    private val domainEventRepository: DomainEventEntryRepository,
    private val alertRepository: DecisionAlertRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val objectMapper: ObjectMapper
) {

    @Transactional
    fun recordInspection(request: CreateInspectionRequest): InspectionResponse {
        val hive = hiveRepository.findById(request.hiveId)
            .orElseThrow { IllegalArgumentException("Hive not found with ID: ${request.hiveId}") }

        val inspection = inspectionRepository.save(
            Inspection(
                id = UUID.randomUUID(),
                hive = hive,
                inspectionDate = Instant.now(),
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

        // Persist immutable event log with JSONB payload
        val payloadJson = objectMapper.writeValueAsString(domainEvent)
        domainEventRepository.save(
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

        // Fetch unacknowledged alerts produced during this cycle for immediate client feedback
        val activeAlerts = alertRepository.findByHiveIdAndIsAcknowledgedFalseOrderByCreatedAtDesc(hive.id)
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