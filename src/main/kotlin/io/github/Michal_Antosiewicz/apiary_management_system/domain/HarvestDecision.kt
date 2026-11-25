package io.github.Michal_Antosiewicz.apiary_management_system.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/** One persisted outcome per accepted harvest command; eventId references domain_events. */
@Entity
@Table(name = "harvest_decisions")
class HarvestDecision(
    @Id @Column(name = "event_id")
    val eventId: UUID,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hive_id", nullable = false)
    val hive: Hive,
    @Column(nullable = false)
    val approved: Boolean,
    @Column(name = "reason_code", nullable = false, length = 40)
    val reasonCode: String,
    @Column(nullable = false, columnDefinition = "TEXT")
    val message: String,
    @Column(name = "decided_at", nullable = false)
    val decidedAt: Instant,
    @Column(name = "blocked_until")
    val blockedUntil: Instant? = null
)
