package io.github.Michal_Antosiewicz.apiary_management_system.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "apiaries")
class Apiary(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(nullable = false, length = 100)
    var name: String,

    @Column(nullable = false, precision = 9, scale = 6)
    var latitude: BigDecimal,

    @Column(nullable = false, precision = 9, scale = 6)
    var longitude: BigDecimal,

    @Column(name = "microclimate_notes", columnDefinition = "TEXT")
    var microclimateNotes: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @OneToMany(mappedBy = "apiary", cascade = [CascadeType.ALL], orphanRemoval = true)
    val hives: MutableList<Hive> = mutableListOf()
)