-- 1. Apiary
INSERT INTO apiaries (id, name, latitude, longitude, microclimate_notes)
VALUES (
           'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
           'Experimental Apiary Alpha',
           52.229676,
           21.012229,
           'Suburban apiary, adjacent to orchard, moderate humidity and wind exposure'
       );

-- 2. Hives
INSERT INTO hives (id, apiary_id, hive_number, hive_type, operational_state, biological_state)
VALUES
    -- Hive 1: Swarming risk scenario
    ('b1000000-0000-0000-0000-000000000001', 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11', 'HIVE-01-SWRM', 'Dadant', 'ACTIVE_HONEY_FLOW', 'CONGESTED'),
    -- Hive 2: Sanitary and quarantine guard scenario
    ('b2000000-0000-0000-0000-000000000002', 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11', 'HIVE-02-TRTM', 'Langstroth', 'TREATMENT_IN_PROGRESS', 'NOMINAL'),
    -- Hive 3: Supersedure scenario
    ('b3000000-0000-0000-0000-000000000003', 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11', 'HIVE-03-SPRS', 'Langstroth', 'ACTIVE_HONEY_FLOW', 'NOMINAL'),
    -- Hive 4: Absconding and starvation risk scenario
    ('b4000000-0000-0000-0000-000000000004', 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11', 'HIVE-04-ABSC', 'Dadant', 'ACTIVE_HONEY_FLOW', 'NOMINAL');

-- 3. Queens
INSERT INTO queens (id, hive_id, emergence_year, breed_line, marking_color, is_clipped, is_active)
VALUES
    -- Queen with decreased QMP pheromone level (high swarming tendency)
    ('c1000000-0000-0000-0000-000000000001', 'b1000000-0000-0000-0000-000000000001', 2024, 'Carnica', 'BLUE', false, true),
    -- Active young queen
    ('c2000000-0000-0000-0000-000000000002', 'b2000000-0000-0000-0000-000000000002', 2025, 'Buckfast', 'WHITE', false, true),
    -- Failing queen (causes spotty brood)
    ('c3000000-0000-0000-0000-000000000003', 'b3000000-0000-0000-0000-000000000003', 2023, 'Caucasian', 'RED', false, true),
    -- Queen under pest pressure
    ('c4000000-0000-0000-0000-000000000004', 'b4000000-0000-0000-0000-000000000004', 2025, 'Carnica', 'WHITE', false, true);

-- 4. Veterinary Treatments (HIVE-02 currently under withdrawal restriction)
INSERT INTO treatments (id, hive_id, medicine_name, active_substance, start_date, treatment_end_date, withdrawal_days, withdrawal_end_date)
VALUES (
           'd2000000-0000-0000-0000-000000000001',
           'b2000000-0000-0000-0000-000000000002',
           'Apivar',
           'Amitraz',
           NOW() - INTERVAL '5 days',
           NOW() + INTERVAL '9 days',
           14,
           NOW() + INTERVAL '23 days'
       );

-- 5. Field Inspections (Feature Vectors)
INSERT INTO inspections (
    id, hive_id, inspection_date, frames_of_bees, frames_of_brood,
    honey_stores_kg, free_comb_space_percent, queen_seen, brood_pattern,
    queen_cells_count, queen_cells_placement, queen_cells_stage,
    varroa_mite_count, signs_of_pests, temperament_score, notes
)
VALUES
    -- Swarming inspection: crowded colony, marginal sealed cells, low comb space
    ('e1000000-0000-0000-0000-000000000001', 'b1000000-0000-0000-0000-000000000001',
     NOW() - INTERVAL '1 day', 12, 8, 14.50, 5.00, true, 'SOLID_COMPACT',
     8, 'MARGINAL', 'SEALED', 2, false, 4, 'Severe congestion, festooning bees below bottom bars, multiple swarm cells along bottom comb edges'),

    -- Supersedure inspection: failing queen, spotty brood, few central open cells
    ('e3000000-0000-0000-0000-000000000003', 'b3000000-0000-0000-0000-000000000003',
     NOW() - INTERVAL '2 days', 7, 3, 8.00, 35.00, true, 'SPOTTY',
     2, 'CENTRAL', 'OPEN_WITH_LARVA', 1, false, 5, 'Discontinuous brood pattern, two supersedure cells located in center of the brood frame'),

    -- Absconding risk inspection: heavy varroa infestation, depleted stores, robbing traces
    ('e4000000-0000-0000-0000-000000000004', 'b4000000-0000-0000-0000-000000000004',
     NOW() - INTERVAL '3 hours', 4, 1, 0.80, 60.00, false, 'ABSENT',
     0, 'NONE', 'NONE', 45, true, 2, 'Critical parasite threshold exceeded, near zero honey reserves, erratic defense behavior');

-- 6. Domain Events Stream (JSONB payloads)
INSERT INTO domain_events (id, hive_id, event_type, payload, occurred_at)
VALUES
    (
        'f1000000-0000-0000-0000-000000000001',
        'b1000000-0000-0000-0000-000000000001',
        'SwarmIndicatorObservedEvent',
        '{"cellsCount": 8, "placement": "MARGINAL", "stage": "SEALED", "freeCombSpacePercent": 5.0}',
        NOW() - INTERVAL '1 day'
    ),
    (
        'f2000000-0000-0000-0000-000000000002',
        'b2000000-0000-0000-0000-000000000002',
        'TreatmentAppliedEvent',
        '{"medicineName": "Apivar", "activeSubstance": "Amitraz", "startDate": "2025-01-01T00:00:00Z", "treatmentEndDate": "2025-01-15T00:00:00Z", "withdrawalEndDate": "2025-01-29T00:00:00Z"}',
        NOW() - INTERVAL '5 days'
    );

-- 7. Generated Decision Alerts (DSS Inference Outputs)
INSERT INTO decision_alerts (id, hive_id, alert_type, severity, risk_score, recommendation_text)
VALUES
    (
        'a1000000-0000-0000-0000-000000000001',
        'b1000000-0000-0000-0000-000000000001',
        'REPRODUCTIVE_SWARM',
        'CRITICAL',
        0.920,
        'Imminent swarming risk: sealed marginal queen cells detected in congested brood chamber. Immediate artificial swarm or volume expansion required.'
    ),
    (
        'a3000000-0000-0000-0000-000000000003',
        'b3000000-0000-0000-0000-000000000003',
        'SUPERSEDURE',
        'INFO',
        0.810,
        'Active supersedure detected: central queen cells and irregular brood pattern. Do not remove queen cells to allow natural queen replacement.'
    );