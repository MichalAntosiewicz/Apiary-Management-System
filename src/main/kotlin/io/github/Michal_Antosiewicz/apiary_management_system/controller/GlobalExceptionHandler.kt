package io.github.Michal_Antosiewicz.apiarymanagementsystem.controller

import io.github.Michal_Antosiewicz.apiarymanagementsystem.domain.SanitaryViolationException
import io.github.Michal_Antosiewicz.apiarymanagementsystem.dto.ErrorResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(SanitaryViolationException::class)
    fun handleSanitaryViolation(ex: SanitaryViolationException): ResponseEntity<ErrorResponse> {
        val errorResponse = ErrorResponse(
            status = HttpStatus.CONFLICT.value(),
            error = "SANITARY_WITHDRAWAL_VIOLATION",
            message = ex.message ?: "Operation prohibited due to active veterinary withdrawal period.",
            details = mapOf(
                "hiveId" to ex.hiveId,
                "medicineName" to ex.medicineName,
                "withdrawalEndDate" to ex.withdrawalEndDate
            )
        )
        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorResponse)
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ResponseEntity<ErrorResponse> {
        val errorResponse = ErrorResponse(
            status = HttpStatus.BAD_REQUEST.value(),
            error = "INVALID_REQUEST",
            message = ex.message ?: "Bad request payload"
        )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse)
    }
}