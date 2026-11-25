package io.github.Michal_Antosiewicz.apiary_management_system.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

import org.hibernate.annotations.JdbcType
import org.hibernate.dialect.PostgreSQLEnumJdbcType

@Entity
@Table(name = "decision_alerts")
class DecisionAlert(
    @Id
    val id: UUID = UUID.randomUUID(),

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hive_id", nullable = false)
    var hive: Hive,

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType::class)
    @Column(name = "alert_type", nullable = false)
    var alertType: AlertType,

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType::class)
    @Column(name = "severity", nullable = false)
    var severity: AlertSeverity,

    @Column(name = "risk_score", nullable = false, precision = 4, scale = 3)
    var riskScore: BigDecimal,

    @Column(name = "recommendation_text", nullable = false, columnDefinition = "TEXT")
    var recommendationText: String,

    @Column(name = "is_acknowledged", nullable = false)
    var isAcknowledged: Boolean = false,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "event_id")
    val eventId: UUID? = null
)
