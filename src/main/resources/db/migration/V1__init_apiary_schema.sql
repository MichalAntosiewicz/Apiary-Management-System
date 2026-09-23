-- Enums
CREATE TYPE operational_state_enum AS ENUM (
    'PREPARATION',
    'ACTIVE_HONEY_FLOW',
    'TREATMENT_IN_PROGRESS',
    'WITHDRAWAL_PERIOD',
    'WINTERING',
    'DECOMMISSIONED'
);

CREATE TYPE biological_state_enum AS ENUM (
    'NOMINAL',
    'CONGESTED',
    'SWARM_PREPARATION',
    'SUPERSEDURE_ACTIVE',
    'ABSCONDING_RISK',
    'SWARMED'
);

CREATE TYPE cell_placement_enum AS ENUM ('NONE', 'MARGINAL', 'CENTRAL');
CREATE TYPE cell_stage_enum AS ENUM ('NONE', 'CUP', 'OPEN_WITH_LARVA', 'SEALED');
CREATE TYPE brood_pattern_enum AS ENUM ('SOLID_COMPACT', 'SPOTTY', 'DRONE_BROOD_ONLY', 'ABSENT');

CREATE TYPE alert_severity_enum AS ENUM ('INFO', 'WARNING', 'CRITICAL');
CREATE TYPE alert_type_enum AS ENUM (
    'REPRODUCTIVE_SWARM',
    'COLONY_ABSCONDING',
    'SUPERSEDURE',
    'SANITARY_VIOLATION',
    'STARVATION'
);

-- Core Infrastructure

CREATE TABLE apiaries (
                          id UUID PRIMARY KEY,
                          name VARCHAR(100) NOT NULL,
                          latitude DECIMAL(9, 6) NOT NULL,
                          longitude DECIMAL(9, 6) NOT NULL,
                          microclimate_notes TEXT,
                          created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE hives (
                       id UUID PRIMARY KEY,
                       apiary_id UUID NOT NULL REFERENCES apiaries(id) ON DELETE CASCADE,
                       hive_number VARCHAR(50) NOT NULL,
                       hive_type VARCHAR(50) NOT NULL,
                       operational_state operational_state_enum NOT NULL DEFAULT 'PREPARATION',
                       biological_state biological_state_enum NOT NULL DEFAULT 'NOMINAL',
                       created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE queens (
                        id UUID PRIMARY KEY,
                        hive_id UUID NOT NULL REFERENCES hives(id) ON DELETE CASCADE,
                        emergence_year INT NOT NULL,
                        breed_line VARCHAR(50) NOT NULL,
                        marking_color VARCHAR(20),
                        is_clipped BOOLEAN NOT NULL DEFAULT FALSE,
                        is_active BOOLEAN NOT NULL DEFAULT TRUE,
                        installed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Treatments and quarantines

CREATE TABLE treatments (
                            id UUID PRIMARY KEY,
                            hive_id UUID NOT NULL REFERENCES hives(id) ON DELETE CASCADE,
                            medicine_name VARCHAR(100) NOT NULL,
                            active_substance VARCHAR(100) NOT NULL,
                            start_date TIMESTAMP WITH TIME ZONE NOT NULL,
                            end_date TIMESTAMP WITH TIME ZONE,
                            withdrawal_days INT NOT NULL,
                            withdrawal_end_date TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Field inspections

CREATE TABLE inspections (
                             id UUID PRIMARY KEY,
                             hive_id UUID NOT NULL REFERENCES hives(id) ON DELETE CASCADE,
                             inspection_date TIMESTAMP WITH TIME ZONE NOT NULL,

    -- Population and comb occupancy metrics
                             frames_of_bees INT NOT NULL,
                             frames_of_brood INT NOT NULL,
                             honey_stores_kg DECIMAL(5, 2) NOT NULL,
                             free_comb_space_percent DECIMAL(5, 2) NOT NULL,

    -- Diagnostic markers for swarming and supersedure differentiation
                             queen_seen BOOLEAN NOT NULL DEFAULT FALSE,
                             brood_pattern brood_pattern_enum NOT NULL,
                             queen_cells_count INT NOT NULL DEFAULT 0,
                             queen_cells_placement cell_placement_enum NOT NULL DEFAULT 'NONE',
                             queen_cells_stage cell_stage_enum NOT NULL DEFAULT 'NONE',

    -- Stressors, health markers, and absconding risk factors
                             varroa_mite_count INT NOT NULL DEFAULT 0,
                             signs_of_pests BOOLEAN NOT NULL DEFAULT FALSE,
                             temperament_score INT CHECK (temperament_score BETWEEN 1 AND 5),
                             notes TEXT
);

-- Domain Events Stream

CREATE TABLE domain_events (
                               id UUID PRIMARY KEY,
                               hive_id UUID NOT NULL REFERENCES hives(id) ON DELETE CASCADE,
                               event_type VARCHAR(100) NOT NULL,
                               payload JSONB NOT NULL,
                               occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
                               recorded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- GIN index
CREATE INDEX idx_domain_events_payload ON domain_events USING GIN (payload);
CREATE INDEX idx_domain_events_hive_occurred ON domain_events (hive_id, occurred_at DESC);

-- Finite-State machine transition audit trail

CREATE TABLE hive_state_transitions (
                                        id UUID PRIMARY KEY,
                                        hive_id UUID NOT NULL REFERENCES hives(id) ON DELETE CASCADE,
                                        fsm_type VARCHAR(50) NOT NULL, -- e.g., 'OPERATIONAL_LIFECYCLE' or 'BIOLOGICAL_DYNAMICS'
                                        from_state VARCHAR(50) NOT NULL,
                                        to_state VARCHAR(50) NOT NULL,
                                        trigger_event VARCHAR(100) NOT NULL,
                                        reason TEXT,
                                        transition_timestamp TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_state_transitions_hive ON hive_state_transitions (hive_id, transition_timestamp DESC);

-- Decision Support System, alerts and advisory logs

CREATE TABLE decision_alerts (
                                 id UUID PRIMARY KEY,
                                 hive_id UUID NOT NULL REFERENCES hives(id) ON DELETE CASCADE,
                                 alert_type alert_type_enum NOT NULL,
                                 severity alert_severity_enum NOT NULL,
                                 risk_score DECIMAL(4, 3) NOT NULL,
                                 recommendation_text TEXT NOT NULL,
                                 is_acknowledged BOOLEAN NOT NULL DEFAULT FALSE,
                                 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_decision_alerts_hive_unack ON decision_alerts (hive_id, is_acknowledged);