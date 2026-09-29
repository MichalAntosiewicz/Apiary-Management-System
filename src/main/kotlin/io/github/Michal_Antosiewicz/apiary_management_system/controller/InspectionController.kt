package io.github.Michal_Antosiewicz.apiarymanagementsystem.controller

import io.github.Michal_Antosiewicz.apiarymanagementsystem.dto.CreateInspectionRequest
import io.github.Michal_Antosiewicz.apiarymanagementsystem.dto.InspectionResponse
import io.github.Michal_Antosiewicz.apiarymanagementsystem.service.InspectionService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/inspections")
class InspectionController(
    private val inspectionService: InspectionService
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun recordInspection(@RequestBody request: CreateInspectionRequest): InspectionResponse {
        return inspectionService.recordInspection(request)
    }
}