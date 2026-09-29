package io.github.Michal_Antosiewicz.apiary_management_system.domain

enum class OperationalState {
    PREPARATION,
    ACTIVE_HONEY_FLOW,
    TREATMENT_IN_PROGRESS,
    WITHDRAWAL_PERIOD,
    WINTERING,
    DECOMMISSIONED
}

enum class BiologicalState {
    NOMINAL,
    CONGESTED,
    SWARM_PREPARATION,
    SUPERSEDURE_ACTIVE,
    ABSCONDING_RISK,
    SWARMED
}

enum class CellPlacement {
    NONE,
    MARGINAL,
    CENTRAL
}

enum class CellStage {
    NONE,
    CUP,
    OPEN_WITH_LARVA,
    SEALED
}

enum class BroodPattern {
    SOLID_COMPACT,
    SPOTTY,
    DRONE_BROOD_ONLY,
    ABSENT
}

enum class AlertSeverity {
    INFO,
    WARNING,
    CRITICAL
}

enum class AlertType {
    REPRODUCTIVE_SWARM,
    COLONY_ABSCONDING,
    SUPERSEDURE,
    SANITARY_VIOLATION,
    STARVATION
}