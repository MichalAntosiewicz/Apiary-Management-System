package io.github.Michal_Antosiewicz.apiary_management_system.domain

import jakarta.persistence.*
import org.hibernate.annotations.JdbcType
import org.hibernate.dialect.PostgreSQLEnumJdbcType
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "inspections")
class Inspection(
    @Id
    val id: UUID = UUID.randomUUID(),

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hive_id", nullable = false)
    var hive: Hive,

    @Column(name = "inspection_date", nullable = false)
    var inspectionDate: Instant = Instant.now(),

    @Column(name = "frames_of_bees", nullable = false)
    var framesOfBees: Int,

    @Column(name = "frames_of_brood", nullable = false)
    var framesOfBrood: Int,

    @Column(name = "honey_stores_kg", nullable = false, precision = 5, scale = 2)
    var honeyStoresKg: BigDecimal,

    @Column(name = "free_comb_space_percent", nullable = false, precision = 5, scale = 2)
    var freeCombSpacePercent: BigDecimal,

    @Column(name = "queen_seen", nullable = false)
    var queenSeen: Boolean = false,

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType::class)
    @Column(name = "brood_pattern", nullable = false)
    var broodPattern: BroodPattern,

    @Column(name = "queen_cells_count", nullable = false)
    var queenCellsCount: Int = 0,

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType::class)
    @Column(name = "queen_cells_placement", nullable = false)
    var queenCellsPlacement: CellPlacement = CellPlacement.NONE,

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType::class)
    @Column(name = "queen_cells_stage", nullable = false)
    var queenCellsStage: CellStage = CellStage.NONE,

    @Column(name = "varroa_mite_count", nullable = false)
    var varroaMiteCount: Int = 0,

    @Column(name = "signs_of_pests", nullable = false)
    var signsOfPests: Boolean = false,

    @Column(name = "temperament_score")
    var temperamentScore: Int? = null,

    @Column(columnDefinition = "TEXT")
    var notes: String? = null
)