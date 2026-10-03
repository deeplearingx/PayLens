ALTER TABLE paylens_analysis_task ADD COLUMN IF NOT EXISTS snapshot_hash varchar(64);
ALTER TABLE paylens_analysis_task ADD COLUMN IF NOT EXISTS snapshot_at timestamptz;
ALTER TABLE paylens_analysis_task ADD COLUMN IF NOT EXISTS model_id varchar(128);
ALTER TABLE paylens_analysis_task ADD COLUMN IF NOT EXISTS model_configuration_id varchar(64);
ALTER TABLE paylens_analysis_task ADD COLUMN IF NOT EXISTS prompt_version varchar(128);
ALTER TABLE paylens_analysis_task ADD COLUMN IF NOT EXISTS output_schema_version varchar(64);
ALTER TABLE paylens_analysis_task ADD COLUMN IF NOT EXISTS knowledge_version varchar(64);
ALTER TABLE paylens_analysis_task ADD COLUMN IF NOT EXISTS retrieved_knowledge jsonb;

CREATE TABLE IF NOT EXISTS paylens_analysis_attempt (
    id bigserial PRIMARY KEY,
    task_id varchar(36) NOT NULL,
    attempt_no integer NOT NULL,
    status varchar(16) NOT NULL,
    started_at timestamptz NOT NULL,
    completed_at timestamptz,
    duration_ms bigint,
    failure_code varchar(128),
    model_id varchar(128),
    returned_model_id varchar(128),
    prompt_tokens bigint,
    completion_tokens bigint,
    prompt_version varchar(128),
    knowledge_version varchar(64),
    UNIQUE (task_id, attempt_no)
);

ALTER TABLE paylens_analysis_attempt ADD COLUMN IF NOT EXISTS returned_model_id varchar(128);
ALTER TABLE paylens_analysis_attempt ADD COLUMN IF NOT EXISTS prompt_tokens bigint;
ALTER TABLE paylens_analysis_attempt ADD COLUMN IF NOT EXISTS completion_tokens bigint;
CREATE INDEX IF NOT EXISTS idx_paylens_attempt_task ON paylens_analysis_attempt(task_id, attempt_no);

CREATE TABLE IF NOT EXISTS paylens_schema_migration (
    version varchar(32) PRIMARY KEY,
    applied_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO paylens_schema_migration(version) VALUES ('V002') ON CONFLICT DO NOTHING;
