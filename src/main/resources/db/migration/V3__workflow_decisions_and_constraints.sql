-- Existing demonstration records remain nullable; all new application decisions carry event IDs.
ALTER TABLE decision_alerts ADD COLUMN event_id UUID REFERENCES domain_events(id);
ALTER TABLE hive_state_transitions ADD COLUMN event_id UUID REFERENCES domain_events(id);
CREATE INDEX idx_alert_event ON decision_alerts(event_id);
CREATE INDEX idx_transition_event ON hive_state_transitions(event_id);
CREATE TABLE harvest_decisions (
    event_id UUID PRIMARY KEY REFERENCES domain_events(id),
    hive_id UUID NOT NULL REFERENCES hives(id),
    approved BOOLEAN NOT NULL,
    reason_code VARCHAR(40) NOT NULL,
    message TEXT NOT NULL,
    decided_at TIMESTAMP WITH TIME ZONE NOT NULL,
    blocked_until TIMESTAMP WITH TIME ZONE
);
ALTER TABLE treatments ADD CONSTRAINT treatment_nonnegative_withdrawal CHECK (withdrawal_days >= 0);
ALTER TABLE treatments ADD CONSTRAINT treatment_dates_ordered CHECK (
    start_date < treatment_end_date AND treatment_end_date <= withdrawal_end_date);
ALTER TABLE inspections ADD CONSTRAINT inspection_nonnegative_counts CHECK (
    frames_of_bees >= 0 AND frames_of_brood >= 0 AND queen_cells_count >= 0 AND varroa_mite_count >= 0);
ALTER TABLE inspections ADD CONSTRAINT inspection_valid_resources CHECK (
    honey_stores_kg >= 0 AND free_comb_space_percent BETWEEN 0 AND 100);
