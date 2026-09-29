package io.github.Michal_Antosiewicz.apiary_management_system.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "queens")
class Queen(
    @Id
    val id: UUID = UUID.randomUUID(),

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hive_id", nullable = false)
    var hive: Hive,

    @Column(name = "emergence_year", nullable = false)
    var emergenceYear: Int,

    @Column(name = "breed_line", nullable = false, length = 50)
    var breedLine: String,

    @Column(name = "marking_color", length = 20)
    var markingColor: String? = null,

    @Column(name = "is_clipped", nullable = false)
    var isClipped: Boolean = false,

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true,

    @Column(name = "installed_at", nullable = false, updatable = false)
    val installedAt: Instant = Instant.now()
)