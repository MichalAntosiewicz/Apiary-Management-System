package io.github.Michal_Antosiewicz.apiary_management_system.controller
import io.github.Michal_Antosiewicz.apiary_management_system.dto.*
import io.github.Michal_Antosiewicz.apiary_management_system.repository.DecisionAlertRepository
import io.github.Michal_Antosiewicz.apiary_management_system.service.HiveOperationService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/hives")
class HiveOperationController(private val operations: HiveOperationService,
    private val alertRepository: DecisionAlertRepository) {
    // The service transaction commits before the controller emits HTTP 200/409.
    @PostMapping("/{hiveId}/harvest")
    fun attemptHoneyHarvest(@PathVariable hiveId: UUID,
        @Valid @RequestBody(required = false) request: HarvestHoneyRequest?): ResponseEntity<HarvestHoneyResponse> {
        val decision = operations.attemptHoneyHarvest(hiveId, request ?: HarvestHoneyRequest())
        return ResponseEntity.status(if (decision.status == "APPROVED") HttpStatus.OK else HttpStatus.CONFLICT)
            .body(decision)
    }
    @PostMapping("/{hiveId}/treatments")
    @ResponseStatus(HttpStatus.CREATED)
    fun applyTreatment(@PathVariable hiveId: UUID,
        @Valid @RequestBody request: ApplyTreatmentRequest): TreatmentResponse = operations.applyTreatment(hiveId, request)
    @GetMapping("/{hiveId}/alerts")
    fun getActiveAlerts(@PathVariable hiveId: UUID): List<InspectionAlertDto> =
        alertRepository.findByHiveIdAndIsAcknowledgedFalseOrderByCreatedAtDesc(hiveId).map {
            InspectionAlertDto(it.alertType, it.severity, it.riskScore, it.recommendationText)
        }
}
