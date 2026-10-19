BEGIN;

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ========== 用户与组织 ==========
CREATE TABLE users (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    oidc_issuer     text NOT NULL,
    oidc_subject    text NOT NULL,
    display_name    text NOT NULL,
    email           text,
    status          text NOT NULL DEFAULT 'active'
                    CHECK (status IN ('active', 'disabled', 'pending')),
    last_login_at   timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (oidc_issuer, oidc_subject)
);
CREATE INDEX idx_users_status ON users(status);

CREATE TABLE organizations (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    slug            text NOT NULL UNIQUE,
    name            text NOT NULL,
    status          text NOT NULL DEFAULT 'active'
                    CHECK (status IN ('active', 'suspended', 'archived')),
    policy_json     jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_by      uuid REFERENCES users(id) ON DELETE SET NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE organization_memberships (
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role            text NOT NULL CHECK (role IN ('owner', 'admin', 'publisher', 'reviewer', 'member', 'viewer')),
    joined_at       timestamptz NOT NULL DEFAULT now(),
    created_by      uuid REFERENCES users(id) ON DELETE SET NULL,
    PRIMARY KEY (organization_id, user_id)
);
CREATE INDEX idx_org_memberships_user ON organization_memberships(user_id, organization_id);

CREATE TABLE teams (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    slug            text NOT NULL,
    name            text NOT NULL,
    description     text,
    status          text NOT NULL DEFAULT 'active'
                    CHECK (status IN ('active', 'archived')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, slug),
    UNIQUE (organization_id, id)
);

CREATE TABLE team_memberships (
    team_id         uuid NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
    user_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role            text NOT NULL CHECK (role IN ('admin', 'publisher', 'reviewer', 'member', 'viewer')),
    joined_at       timestamptz NOT NULL DEFAULT now(),
    added_by        uuid REFERENCES users(id) ON DELETE SET NULL,
    PRIMARY KEY (team_id, user_id)
);
CREATE INDEX idx_team_memberships_user ON team_memberships(user_id, team_id);

-- ========== Skill 主体与来源 ==========
CREATE TABLE skills (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    slug            text NOT NULL,
    name            text NOT NULL,
    description     text NOT NULL DEFAULT '',
    owner_user_id   uuid REFERENCES users(id) ON DELETE SET NULL,
    organization_id uuid REFERENCES organizations(id) ON DELETE RESTRICT,
    owner_team_id   uuid,
    visibility      text NOT NULL DEFAULT 'private'
                    CHECK (visibility IN ('private', 'team', 'organization', 'public')),
    status          text NOT NULL DEFAULT 'active'
                    CHECK (status IN ('active', 'archived', 'quarantined')),
    tags            text[] NOT NULL DEFAULT '{}',
    metadata_json   jsonb NOT NULL DEFAULT '{}'::jsonb,
    latest_version  text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    archived_at     timestamptz,
    CHECK (owner_user_id IS NOT NULL OR organization_id IS NOT NULL),
    CHECK (visibility <> 'team' OR (owner_team_id IS NOT NULL AND organization_id IS NOT NULL)),
    FOREIGN KEY (organization_id, owner_team_id)
        REFERENCES teams(organization_id, id) DEFERRABLE INITIALLY IMMEDIATE
);
CREATE UNIQUE INDEX uq_skills_personal_slug
    ON skills(owner_user_id, slug) WHERE organization_id IS NULL AND owner_user_id IS NOT NULL;
CREATE UNIQUE INDEX uq_skills_org_slug
    ON skills(organization_id, slug) WHERE organization_id IS NOT NULL;
CREATE INDEX idx_skills_visibility_status ON skills(visibility, status);
CREATE INDEX idx_skills_owner_user ON skills(owner_user_id, updated_at DESC);
CREATE INDEX idx_skills_org_team ON skills(organization_id, owner_team_id);
CREATE INDEX idx_skills_tags ON skills USING gin(tags);
CREATE INDEX idx_skills_metadata ON skills USING gin(metadata_json);

CREATE TABLE skill_sources (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    skill_id            uuid NOT NULL REFERENCES skills(id) ON DELETE CASCADE,
    source_type         text NOT NULL CHECK (source_type IN ('local_import', 'git', 'registry', 'archive')),
    provider            text CHECK (provider IN ('github', 'gitlab', 'gitee', 'generic_git', 'local', 'skillmesh')),
    repository_url      text,
    subpath             text,
    ref_type            text CHECK (ref_type IN ('branch', 'tag', 'commit', 'default', 'local')),
    ref_value           text,
    resolved_commit     text,
    license_expression  text,
    license_status      text NOT NULL DEFAULT 'unknown'
                        CHECK (license_status IN ('unknown', 'detected', 'approved', 'restricted', 'incompatible')),
    credential_ref      text, -- 只保存受控 Secret/本机凭据引用，不保存凭据本体
    source_fingerprint  text,
    created_by          uuid REFERENCES users(id) ON DELETE SET NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CHECK ((source_type = 'git' AND repository_url IS NOT NULL) OR source_type <> 'git'),
    CHECK (repository_url IS NULL OR repository_url !~* '://[^/]*:[^/@]+@'),
    CHECK (subpath IS NULL OR (subpath !~ '(^/|(^|/)\.\.(/|$))'))
);
CREATE INDEX idx_skill_sources_skill ON skill_sources(skill_id, created_at DESC);
CREATE INDEX idx_skill_sources_repo ON skill_sources(repository_url) WHERE repository_url IS NOT NULL;

-- ========== 制品、版本与扫描 ==========
CREATE TABLE artifacts (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    sha256          char(64) NOT NULL UNIQUE,
    storage_key     text NOT NULL UNIQUE,
    content_type    text NOT NULL DEFAULT 'application/zip',
    size_bytes      bigint NOT NULL CHECK (size_bytes >= 0),
    file_count      integer NOT NULL CHECK (file_count >= 0),
    manifest_json   jsonb NOT NULL DEFAULT '[]'::jsonb,
    signature       text,
    signature_key_id text,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE skill_versions (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    skill_id            uuid NOT NULL REFERENCES skills(id) ON DELETE RESTRICT,
    source_id           uuid REFERENCES skill_sources(id) ON DELETE SET NULL,
    version             text NOT NULL,
    channel             text NOT NULL DEFAULT 'stable'
                        CHECK (channel IN ('stable', 'beta', 'dev')),
    source_commit       text,
    artifact_id         uuid REFERENCES artifacts(id) ON DELETE RESTRICT,
    changelog           text NOT NULL DEFAULT '',
    manifest_json       jsonb NOT NULL DEFAULT '{}'::jsonb,
    status              text NOT NULL DEFAULT 'draft'
                        CHECK (status IN ('draft', 'scanning', 'pending_review', 'published', 'rejected', 'revoked', 'yanked')),
    created_by          uuid REFERENCES users(id) ON DELETE SET NULL,
    published_by        uuid REFERENCES users(id) ON DELETE SET NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    published_at        timestamptz,
    revoked_at          timestamptz,
    revoke_reason       text,
    UNIQUE (skill_id, version),
    CHECK ((status <> 'published') OR published_at IS NOT NULL),
    CHECK ((status <> 'revoked') OR revoked_at IS NOT NULL),
    CHECK ((status NOT IN ('published', 'revoked', 'yanked')) OR artifact_id IS NOT NULL)
);
CREATE INDEX idx_skill_versions_skill_created ON skill_versions(skill_id, created_at DESC);
CREATE INDEX idx_skill_versions_status ON skill_versions(status, published_at DESC);
CREATE INDEX idx_skill_versions_artifact ON skill_versions(artifact_id);

ALTER TABLE skills
    ADD CONSTRAINT fk_skills_latest_version
    FOREIGN KEY (id, latest_version) REFERENCES skill_versions(skill_id, version)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE skill_scans (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    skill_version_id    uuid NOT NULL REFERENCES skill_versions(id) ON DELETE CASCADE,
    scanner_name        text NOT NULL,
    scanner_version     text NOT NULL,
    status              text NOT NULL CHECK (status IN ('passed', 'warning', 'failed', 'error')),
    findings_json       jsonb NOT NULL DEFAULT '[]'::jsonb,
    summary             text NOT NULL DEFAULT '',
    started_at          timestamptz NOT NULL DEFAULT now(),
    completed_at        timestamptz,
    CHECK (completed_at IS NULL OR completed_at >= started_at)
);
CREATE INDEX idx_skill_scans_version ON skill_scans(skill_version_id, started_at DESC);

CREATE TABLE skill_reviews (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    skill_version_id    uuid NOT NULL REFERENCES skill_versions(id) ON DELETE CASCADE,
    reviewer_id         uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    decision            text NOT NULL CHECK (decision IN ('approved', 'rejected', 'changes_requested')),
    comment             text NOT NULL DEFAULT '',
    created_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (skill_version_id, reviewer_id)
);
CREATE INDEX idx_skill_reviews_version ON skill_reviews(skill_version_id, created_at DESC);

-- ========== 权限 ==========
CREATE TABLE skill_permissions (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    skill_id        uuid NOT NULL REFERENCES skills(id) ON DELETE CASCADE,
    subject_type    text NOT NULL CHECK (subject_type IN ('user', 'team', 'organization')),
    subject_id      uuid NOT NULL,
    permission      text NOT NULL CHECK (permission IN ('view', 'install', 'publish', 'review', 'admin')),
    granted_by      uuid REFERENCES users(id) ON DELETE SET NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (skill_id, subject_type, subject_id, permission)
);
CREATE INDEX idx_skill_permissions_subject ON skill_permissions(subject_type, subject_id, skill_id);

-- ========== 设备、安装与同步 ==========
CREATE TABLE devices (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_key      text NOT NULL,
    display_name    text NOT NULL DEFAULT '',
    platform        text NOT NULL DEFAULT 'unknown',
    cli_version     text NOT NULL DEFAULT '',
    last_seen_at    timestamptz,
    revoked_at      timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, device_key)
);
CREATE INDEX idx_devices_user ON devices(user_id, last_seen_at DESC);

CREATE TABLE installations (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id           uuid REFERENCES devices(id) ON DELETE SET NULL,
    skill_id            uuid NOT NULL REFERENCES skills(id) ON DELETE RESTRICT,
    skill_version_id    uuid REFERENCES skill_versions(id) ON DELETE SET NULL,
    source_id           uuid REFERENCES skill_sources(id) ON DELETE SET NULL,
    agent               text NOT NULL CHECK (agent IN ('claude-code', 'codex', 'cursor', 'generic')),
    install_scope       text NOT NULL CHECK (install_scope IN ('user', 'project', 'custom')),
    project_root        text,
    target_path         text NOT NULL,
    installed_version   text,
    installed_commit    text,
    installed_sha256    char(64),
    update_policy       text NOT NULL DEFAULT 'notify'
                        CHECK (update_policy IN ('manual', 'notify', 'auto_safe')),
    local_modified      boolean NOT NULL DEFAULT false,
    status              text NOT NULL DEFAULT 'active'
                        CHECK (status IN ('active', 'missing', 'conflict', 'uninstalled', 'blocked')),
    installed_at        timestamptz NOT NULL DEFAULT now(),
    last_checked_at     timestamptz,
    last_updated_at     timestamptz,
    UNIQUE (user_id, device_id, target_path, skill_id)
);
CREATE INDEX idx_installations_user ON installations(user_id, status);
CREATE INDEX idx_installations_skill ON installations(skill_id, status);
CREATE INDEX idx_installations_device ON installations(device_id, last_checked_at DESC);

CREATE TABLE sync_states (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id           uuid NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    skill_id            uuid NOT NULL REFERENCES skills(id) ON DELETE CASCADE,
    local_path          text NOT NULL,
    local_revision      text,
    cloud_revision      text,
    base_revision       text,
    local_manifest_hash char(64),
    cloud_manifest_hash char(64),
    status              text NOT NULL DEFAULT 'in_sync'
                        CHECK (status IN ('in_sync', 'local_ahead', 'cloud_ahead', 'conflict', 'paused', 'error')),
    last_synced_at      timestamptz,
    last_error_code     text,
    last_error_message  text,
    updated_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (device_id, skill_id, local_path)
);
CREATE INDEX idx_sync_states_device_status ON sync_states(device_id, status);
CREATE INDEX idx_sync_states_skill ON sync_states(skill_id, updated_at DESC);

CREATE TABLE sync_changesets (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id           uuid NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    skill_id            uuid NOT NULL REFERENCES skills(id) ON DELETE CASCADE,
    idempotency_key     text NOT NULL,
    direction           text NOT NULL CHECK (direction IN ('upload', 'download', 'bidirectional')),
    base_revision       text,
    target_revision     text,
    status              text NOT NULL DEFAULT 'queued'
                        CHECK (status IN ('queued', 'processing', 'completed', 'conflict', 'rejected', 'failed', 'cancelled')),
    manifest_json       jsonb NOT NULL DEFAULT '[]'::jsonb,
    summary_json        jsonb NOT NULL DEFAULT '{}'::jsonb,
    error_code          text,
    error_message       text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    started_at          timestamptz,
    completed_at        timestamptz,
    UNIQUE (device_id, idempotency_key),
    CHECK (completed_at IS NULL OR started_at IS NULL OR completed_at >= started_at)
);
CREATE INDEX idx_sync_changesets_device ON sync_changesets(device_id, created_at DESC);
CREATE INDEX idx_sync_changesets_status ON sync_changesets(status, created_at);

CREATE TABLE sync_conflicts (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    changeset_id        uuid NOT NULL REFERENCES sync_changesets(id) ON DELETE CASCADE,
    relative_path       text NOT NULL,
    base_sha256         char(64),
    local_sha256        char(64),
    cloud_sha256        char(64),
    conflict_type       text NOT NULL CHECK (conflict_type IN ('both_modified', 'delete_modify', 'path_collision', 'binary_conflict', 'unknown')),
    status              text NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'resolved_local', 'resolved_cloud', 'resolved_merged', 'dismissed')),
    resolution_json     jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at          timestamptz NOT NULL DEFAULT now(),
    resolved_at         timestamptz,
    UNIQUE (changeset_id, relative_path)
);
CREATE INDEX idx_sync_conflicts_status ON sync_conflicts(status, created_at);

-- ========== 上传会话 ==========
-- 临时上传对象在校验通过后，才能被提升为不可变制品或同步文件对象。
CREATE TABLE upload_sessions (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_user_id       uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id           uuid REFERENCES devices(id) ON DELETE SET NULL,
    purpose             text NOT NULL CHECK (purpose IN ('skill_artifact', 'sync_file')),
    skill_version_id    uuid REFERENCES skill_versions(id) ON DELETE CASCADE,
    changeset_id        uuid REFERENCES sync_changesets(id) ON DELETE CASCADE,
    relative_path       text,
    expected_sha256     char(64),
    expected_size_bytes bigint NOT NULL CHECK (expected_size_bytes >= 0),
    temporary_storage_key text NOT NULL UNIQUE,
    status              text NOT NULL DEFAULT 'created'
                        CHECK (status IN ('created', 'uploaded', 'verifying', 'completed', 'failed', 'expired')),
    expires_at          timestamptz NOT NULL,
    uploaded_at         timestamptz,
    verified_at         timestamptz,
    error_code          text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    CHECK (
      (purpose = 'skill_artifact' AND skill_version_id IS NOT NULL AND changeset_id IS NULL AND relative_path IS NULL)
      OR
      (purpose = 'sync_file' AND skill_version_id IS NULL AND changeset_id IS NOT NULL AND relative_path IS NOT NULL)
    ),
    CHECK (expected_sha256 IS NULL OR expected_sha256 ~ '^[a-f0-9]{64}$')
);
CREATE INDEX idx_upload_sessions_actor ON upload_sessions(actor_user_id, created_at DESC);
CREATE INDEX idx_upload_sessions_status_expiry ON upload_sessions(status, expires_at);
CREATE UNIQUE INDEX uq_sync_upload_path
    ON upload_sessions(changeset_id, relative_path)
    WHERE purpose = 'sync_file';

-- ========== 审计 ==========
CREATE TABLE audit_events (
    id              bigserial PRIMARY KEY,
    occurred_at     timestamptz NOT NULL DEFAULT now(),
    actor_user_id   uuid REFERENCES users(id) ON DELETE SET NULL,
    organization_id uuid REFERENCES organizations(id) ON DELETE SET NULL,
    device_id       uuid REFERENCES devices(id) ON DELETE SET NULL,
    action          text NOT NULL,
    target_type     text NOT NULL,
    target_id       text,
    outcome         text NOT NULL CHECK (outcome IN ('success', 'denied', 'failure')),
    request_id      text,
    ip_address      inet,
    user_agent      text,
    details_json    jsonb NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX idx_audit_events_time ON audit_events(occurred_at DESC);
CREATE INDEX idx_audit_events_actor_time ON audit_events(actor_user_id, occurred_at DESC);
CREATE INDEX idx_audit_events_target ON audit_events(target_type, target_id, occurred_at DESC);
CREATE INDEX idx_audit_events_action_time ON audit_events(action, occurred_at DESC);

-- 常用自动更新时间由应用层统一维护；也可用数据库触发器实现。
COMMIT;
