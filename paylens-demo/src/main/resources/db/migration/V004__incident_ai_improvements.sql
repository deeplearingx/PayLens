CREATE TABLE IF NOT EXISTS paylens_incident_model_call (
    id bigserial PRIMARY KEY,
    analysis_id varchar(36) NOT NULL REFERENCES paylens_incident_analysis(id),
    attempt_no integer NOT NULL,
    call_kind varchar(20) NOT NULL CHECK (call_kind IN ('INITIAL','FORMAT_REPAIR')),
    status varchar(16) NOT NULL,
    started_at timestamptz NOT NULL,
    completed_at timestamptz,
    duration_ms bigint,
    failure_code varchar(128),
    model_id varchar(128),
    prompt_tokens bigint,
    completion_tokens bigint,
    UNIQUE(analysis_id,attempt_no,call_kind)
);
CREATE INDEX IF NOT EXISTS idx_paylens_incident_model_call_analysis ON paylens_incident_model_call(analysis_id,attempt_no);
