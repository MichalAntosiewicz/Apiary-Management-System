package io.github.Michal_Antosiewicz.apiarymanagementsystem.domain

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "domain_events")
class DomainEventEntry(
    @Id
    val id: UUID = UUID.randomUUID(),

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hive_id", nullable = false)
    var hive: Hive,

    @Column(name = "event_type", nullable = false, length = 100)
    var eventType: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    var payload: String,

    @Column(name = "occurred_at", nullable = false)
    var occurredAt: Instant,

    @Column(name = "recorded_at", nullable = false, updatable = false)
    val recordedAt: Instant = Instant.now()
)