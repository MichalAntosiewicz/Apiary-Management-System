package io.github.Michal_Antosiewicz.apiary_management_system.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "treatments")
class Treatment(
    @Id
    val id: UUID = UUID.randomUUID(),

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hive_id", nullable = false)
    var hive: Hive,

    @Column(name = "medicine_name", nullable = false, length = 100)
    var medicineName: String,

    @Column(name = "active_substance", nullable = false, length = 100)
    var activeSubstance: String,

    @Column(name = "start_date", nullable = false)
    var startDate: Instant,

    @Column(name = "end_date")
    var endDate: Instant? = null,

    @Column(name = "withdrawal_days", nullable = false)
    var withdrawalDays: Int,

    @Column(name = "withdrawal_end_date", nullable = false)
    var withdrawalEndDate: Instant
)