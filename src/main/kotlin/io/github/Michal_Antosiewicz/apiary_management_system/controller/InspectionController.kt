package io.github.Michal_Antosiewicz.apiary_management_system.controller

import io.github.Michal_Antosiewicz.apiary_management_system.dto.CreateInspectionRequest
import io.github.Michal_Antosiewicz.apiary_management_system.dto.InspectionResponse
import io.github.Michal_Antosiewicz.apiary_management_system.service.InspectionService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import jakarta.validation.Valid

@RestController
@RequestMapping("/api/v1/inspections")
class InspectionController(
    private val inspectionService: InspectionService
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun recordInspection(@Valid @RequestBody request: CreateInspectionRequest): InspectionResponse {
        return inspectionService.recordInspection(request)
    }
}