package io.github.Michal_Antosiewicz.apiarymanagementsystem.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "hives")
class Hive(
    @Id
    val id: UUID = UUID.randomUUID(),

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "apiary_id", nullable = false)
    var apiary: Apiary,

    @Column(name = "hive_number", nullable = false, length = 50)
    var hiveNumber: String,

    @Column(name = "hive_type", nullable = false, length = 50)
    var hiveType: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "operational_state", nullable = false)
    var operationalState: OperationalState = OperationalState.PREPARATION,

    @Enumerated(EnumType.STRING)
    @Column(name = "biological_state", nullable = false)
    var biologicalState: BiologicalState = BiologicalState.NOMINAL,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
)