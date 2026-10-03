CREATE TABLE IF NOT EXISTS paylens_incident_batch (
    id varchar(36) PRIMARY KEY,
    mch_no varchar(64) NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    scan_from timestamptz NOT NULL,
    scan_to timestamptz NOT NULL,
    status varchar(16) NOT NULL,
    execution_mode varchar(16) NOT NULL,
    input_count integer NOT NULL DEFAULT 0,
    incident_count integer NOT NULL DEFAULT 0,
    snapshot_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    failure_code varchar(128),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (mch_no, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_paylens_incident_batch_mch_created
    ON paylens_incident_batch(mch_no, created_at DESC);

CREATE TABLE IF NOT EXISTS paylens_incident (
    id varchar(36) PRIMARY KEY,
    batch_id varchar(36) NOT NULL REFERENCES paylens_incident_batch(id),
    mch_no varchar(64) NOT NULL,
    group_no integer NOT NULL,
    version integer NOT NULL DEFAULT 1,
    review_status varchar(16) NOT NULL DEFAULT 'OPEN',
    execution_mode varchar(16) NOT NULL,
    algorithm_version varchar(128) NOT NULL,
    grouping_evidence jsonb NOT NULL,
    statistics_json jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_paylens_incident_group
    ON paylens_incident(batch_id, group_no);

CREATE INDEX IF NOT EXISTS idx_paylens_incident_batch_created
    ON paylens_incident(batch_id, created_at, id);
CREATE INDEX IF NOT EXISTS idx_paylens_incident_mch_created
    ON paylens_incident(mch_no, created_at DESC);

CREATE TABLE IF NOT EXISTS paylens_incident_member (
    batch_id varchar(36) NOT NULL REFERENCES paylens_incident_batch(id),
    incident_id varchar(36) NOT NULL REFERENCES paylens_incident(id),
    observation_id varchar(160) NOT NULL,
    observation_json jsonb NOT NULL,
    PRIMARY KEY (batch_id, observation_id)
);

CREATE INDEX IF NOT EXISTS idx_paylens_incident_member_incident
    ON paylens_incident_member(incident_id, observation_id);

CREATE TABLE IF NOT EXISTS paylens_incident_revision (
    id bigserial PRIMARY KEY,
    incident_id varchar(36) NOT NULL REFERENCES paylens_incident(id),
    version integer NOT NULL,
    operation varchar(16) NOT NULL,
    actor varchar(128) NOT NULL,
    remark varchar(500) NOT NULL,
    snapshot_json jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(incident_id, version)
);

CREATE TABLE IF NOT EXISTS paylens_embedding_cache (
    text_sha256 varchar(64) NOT NULL,
    model_sha256 varchar(64) NOT NULL,
    preprocess_version varchar(64) NOT NULL,
    dimensions integer NOT NULL,
    vector_json jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(text_sha256, model_sha256, preprocess_version)
);

CREATE TABLE IF NOT EXISTS paylens_incident_analysis (
    id varchar(36) PRIMARY KEY,
    mch_no varchar(64) NOT NULL,
    incident_id varchar(36) NOT NULL REFERENCES paylens_incident(id),
    incident_version integer NOT NULL,
    reuse_key varchar(64) NOT NULL UNIQUE,
    evidence_hash varchar(64) NOT NULL,
    evidence_snapshot jsonb NOT NULL,
    execution_versions jsonb NOT NULL,
    retrieved_knowledge jsonb NOT NULL DEFAULT '[]'::jsonb,
    status varchar(16) NOT NULL,
    result_json jsonb,
    failure_code varchar(128),
    duration_ms bigint,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_paylens_incident_analysis_incident
    ON paylens_incident_analysis(mch_no, incident_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_paylens_incident_analysis_queue
    ON paylens_incident_analysis(status, created_at);

CREATE TABLE IF NOT EXISTS paylens_incident_analysis_attempt (
    id bigserial PRIMARY KEY,
    analysis_id varchar(36) NOT NULL REFERENCES paylens_incident_analysis(id),
    attempt_no integer NOT NULL,
    status varchar(16) NOT NULL,
    started_at timestamptz NOT NULL,
    completed_at timestamptz,
    duration_ms bigint,
    failure_code varchar(128),
    model_id varchar(128),
    prompt_tokens bigint,
    completion_tokens bigint,
    UNIQUE(analysis_id, attempt_no)
);

CREATE INDEX IF NOT EXISTS idx_paylens_incident_attempt_analysis
    ON paylens_incident_analysis_attempt(analysis_id, attempt_no);
