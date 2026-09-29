package io.github.Michal_Antosiewicz.apiary_management_system.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "hive_state_transitions")
class HiveStateTransition(
    @Id
    val id: UUID = UUID.randomUUID(),

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hive_id", nullable = false)
    var hive: Hive,

    @Column(name = "fsm_type", nullable = false, length = 50)
    var fsmType: String,

    @Column(name = "from_state", nullable = false, length = 50)
    var fromState: String,

    @Column(name = "to_state", nullable = false, length = 50)
    var toState: String,

    @Column(name = "trigger_event", nullable = false, length = 100)
    var triggerEvent: String,

    @Column(columnDefinition = "TEXT")
    var reason: String? = null,

    @Column(name = "transition_timestamp", nullable = false, updatable = false)
    val transitionTimestamp: Instant = Instant.now()
)