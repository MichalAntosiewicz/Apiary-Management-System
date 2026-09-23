package io.github.Michal_Antosiewicz.apiarymanagementsystem.repository

import io.github.Michal_Antosiewicz.apiarymanagementsystem.domain.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.Optional
import java.util.UUID

@Repository
interface ApiaryRepository : JpaRepository<Apiary, UUID>

@Repository
interface HiveRepository : JpaRepository<Hive, UUID> {
    fun findByApiaryId(apiaryId: UUID): List<Hive>
}

@Repository
interface QueenRepository : JpaRepository<Queen, UUID> {
    fun findByHiveIdAndIsActiveTrue(hiveId: UUID): Optional<Queen>
}

@Repository
interface InspectionRepository : JpaRepository<Inspection, UUID> {
    fun findTopByHiveIdOrderByInspectionDateDesc(hiveId: UUID): Optional<Inspection>
    fun findByHiveIdOrderByInspectionDateDesc(hiveId: UUID): List<Inspection>
}

@Repository
interface TreatmentRepository : JpaRepository<Treatment, UUID> {
    @Query("SELECT t FROM Treatment t WHERE t.hive.id = :hiveId AND t.withdrawalEndDate > :now")
    fun findActiveTreatmentsByHiveId(hiveId: UUID, now: Instant = Instant.now()): List<Treatment>
}

@Repository
interface DecisionAlertRepository : JpaRepository<DecisionAlert, UUID> {
    fun findByHiveIdAndIsAcknowledgedFalseOrderByCreatedAtDesc(hiveId: UUID): List<DecisionAlert>
}

@Repository
interface DomainEventEntryRepository : JpaRepository<DomainEventEntry, UUID> {
    fun findByHiveIdOrderByOccurredAtDesc(hiveId: UUID): List<DomainEventEntry>
}

@Repository
interface HiveStateTransitionRepository : JpaRepository<HiveStateTransition, UUID> {
    fun findByHiveIdOrderByTransitionTimestampDesc(hiveId: UUID): List<HiveStateTransition>
}