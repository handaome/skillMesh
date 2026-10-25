-- 全量初始 schema。
--
-- 外键删除策略只有三种：RESTRICT=归属（被引用就不许删），SET NULL=历史记录
-- （人走了记录留着，署名置空），CASCADE=从属（跟着主人一起走）。

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

CREATE TABLE organizations (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    slug            text NOT NULL UNIQUE,
    name            text NOT NULL,
    status          text NOT NULL DEFAULT 'active'
                    CHECK (status IN ('active', 'suspended', 'archived')),
    policy_json     jsonb NOT NULL DEFAULT '{}'::jsonb
                    CHECK (jsonb_typeof(policy_json) = 'object'),
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

-- 邀请成员进组织/团队。
CREATE TABLE org_invitations (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    team_id         uuid REFERENCES teams(id) ON DELETE CASCADE,
    org_role        text NOT NULL DEFAULT 'member'
                    CHECK (org_role IN ('admin', 'publisher', 'reviewer', 'member', 'viewer')),
    team_role       text NOT NULL DEFAULT 'member'
                    CHECK (team_role IN ('admin', 'publisher', 'reviewer', 'member', 'viewer')),
    token_hash      char(64) NOT NULL UNIQUE
                    CHECK (token_hash ~ '^[a-f0-9]{64}$'),
    status          text NOT NULL DEFAULT 'pending'
                    CHECK (status IN ('pending', 'accepted', 'revoked', 'expired')),
    invited_by      uuid REFERENCES users(id) ON DELETE SET NULL,
    accepted_by     uuid REFERENCES users(id) ON DELETE SET NULL,
    subject         text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL,
    accepted_at     timestamptz
);
CREATE INDEX idx_org_invitations_org ON org_invitations(organization_id, created_at DESC, id DESC);

-- ========== Skill 主体与来源 ==========
CREATE TABLE skills (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    slug            text NOT NULL,
    name            text NOT NULL,
    description     text NOT NULL DEFAULT '',
    -- 属主不可被删走（RESTRICT）：SET NULL 会撞上下面「属主或组织至少有一个」的
    -- CHECK，报错还难懂。要删这个人，先把他的 Skill 转走或删掉。
    owner_user_id   uuid REFERENCES users(id) ON DELETE RESTRICT,
    organization_id uuid REFERENCES organizations(id) ON DELETE RESTRICT,
    owner_team_id   uuid,
    visibility      text NOT NULL DEFAULT 'private'
                    CHECK (visibility IN ('private', 'team', 'organization', 'public')),
    status          text NOT NULL DEFAULT 'active'
                    CHECK (status IN ('active', 'archived', 'quarantined')),
    tags            text[] NOT NULL DEFAULT '{}',
    -- jsonb 的类型必须钉死：实体按 Map 读，写进去一个数组，Hibernate 读的时候会抛
    -- "Could not deserialize string to java type: java.util.Map"。以前 manifest_json
    -- 就这么炸过一次（对象当数组读）。类型对不上时宁可写不进来。
    metadata_json   jsonb NOT NULL DEFAULT '{}'::jsonb
                    CHECK (jsonb_typeof(metadata_json) = 'object'),
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
CREATE INDEX idx_skills_owner_user ON skills(owner_user_id, updated_at DESC);
CREATE INDEX idx_skills_org_team ON skills(organization_id, owner_team_id);
CREATE INDEX idx_skills_tags ON skills USING gin(tags);
CREATE INDEX idx_skills_id_text ON skills ((id::text));

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

-- ========== 制品、版本与扫描 ==========
CREATE TABLE artifacts (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    sha256          char(64) NOT NULL UNIQUE
                    CHECK (sha256 ~ '^[a-f0-9]{64}$'),
    storage_key     text NOT NULL UNIQUE,
    content_type    text NOT NULL DEFAULT 'application/zip',
    size_bytes      bigint NOT NULL CHECK (size_bytes >= 0),
    file_count      integer NOT NULL CHECK (file_count >= 0),
    manifest_json   jsonb NOT NULL DEFAULT '[]'::jsonb
                    CHECK (jsonb_typeof(manifest_json) = 'array'),
    signature       text,
    signature_key_id text,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE skill_versions (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    skill_id            uuid NOT NULL REFERENCES skills(id) ON DELETE RESTRICT,
    source_id           uuid REFERENCES skill_sources(id) ON DELETE SET NULL,
    version             text NOT NULL,
    base_revision       char(64)
                        CHECK (base_revision IS NULL OR base_revision ~ '^[a-f0-9]{64}$'),
    channel             text NOT NULL DEFAULT 'stable'
                        CHECK (channel IN ('stable', 'beta', 'dev')),
    source_commit       text,
    artifact_id         uuid REFERENCES artifacts(id) ON DELETE RESTRICT,
    changelog           text NOT NULL DEFAULT '',
    manifest_json       jsonb NOT NULL DEFAULT '[]'::jsonb
                        CHECK (jsonb_typeof(manifest_json) = 'array'),
    status              text NOT NULL DEFAULT 'draft'
                        CHECK (status IN ('draft', 'scanning', 'pending_review', 'published', 'rejected', 'quarantined', 'revoked', 'yanked')),
    created_by          uuid REFERENCES users(id) ON DELETE SET NULL,
    published_by        uuid REFERENCES users(id) ON DELETE SET NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    published_at        timestamptz,
    submitted_at        timestamptz,
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
    findings_json       jsonb NOT NULL DEFAULT '[]'::jsonb
                        CHECK (jsonb_typeof(findings_json) = 'array'),
    summary             text NOT NULL DEFAULT '',
    started_at          timestamptz NOT NULL DEFAULT now(),
    completed_at        timestamptz,
    CHECK (completed_at IS NULL OR completed_at >= started_at)
);
CREATE INDEX idx_skill_scans_version ON skill_scans(skill_version_id, started_at DESC);

CREATE TABLE skill_reviews (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    skill_version_id    uuid NOT NULL REFERENCES skill_versions(id) ON DELETE CASCADE,
    -- RESTRICT 而不是 SET NULL：existsBySkillVersionIdAndReviewerId 靠这一列判断
    -- 「这个人审没审过」。置成 NULL 就分不出「没审过」和「审过但人没了」。
    reviewer_id         uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    decision            text NOT NULL CHECK (decision IN ('approved', 'rejected', 'changes_requested')),
    comment             text NOT NULL DEFAULT '',
    created_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (skill_version_id, reviewer_id)
);
CREATE INDEX idx_skill_reviews_version ON skill_reviews(skill_version_id, created_at DESC);

-- 权限不单独建表：谁能看、谁能装、谁能发，由 Skill 的归属与 visibility，
-- 加上 organization_memberships / team_memberships 里的角色推导出来。
-- 真要做「把某一个 Skill 单独授权给某个人」时再单独加表，别提前建一张没人读的。

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

CREATE TABLE installations (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- 设备必须明确。应用层靠 (user_id, device_id, target_path, skill_id) 判重，
    -- device_id 为 NULL 时该条件恒不成立，DB 的唯一约束也因 NULL 不参与比较而失效——
    -- 两层防护同时破掉，重复登记会插出多行。设备删除时连带删掉安装记录。
    device_id           uuid NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    skill_id            uuid NOT NULL REFERENCES skills(id) ON DELETE RESTRICT,
    skill_version_id    uuid REFERENCES skill_versions(id) ON DELETE SET NULL,
    source_id           uuid REFERENCES skill_sources(id) ON DELETE SET NULL,
    agent               text NOT NULL CHECK (agent IN ('claude-code', 'codex', 'cursor', 'generic')),
    install_scope       text NOT NULL CHECK (install_scope IN ('user', 'project', 'custom')),
    project_root        text,
    target_path         text NOT NULL,
    installed_version   text,
    installed_commit    text,
    installed_sha256    char(64)
                        CHECK (installed_sha256 IS NULL OR installed_sha256 ~ '^[a-f0-9]{64}$'),
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

CREATE TABLE sync_states (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id           uuid NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    skill_id            uuid NOT NULL REFERENCES skills(id) ON DELETE CASCADE,
    local_path          text NOT NULL,
    local_revision      text,
    cloud_revision      text,
    base_revision       text,
    local_manifest_hash char(64)
                        CHECK (local_manifest_hash IS NULL OR local_manifest_hash ~ '^[a-f0-9]{64}$'),
    cloud_manifest_hash char(64)
                        CHECK (cloud_manifest_hash IS NULL OR cloud_manifest_hash ~ '^[a-f0-9]{64}$'),
    status              text NOT NULL DEFAULT 'in_sync'
                        CHECK (status IN ('in_sync', 'local_ahead', 'cloud_ahead', 'conflict', 'paused', 'error')),
    last_synced_at      timestamptz,
    last_error_code     text,
    last_error_message  text,
    updated_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (device_id, skill_id, local_path)
);

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
    manifest_json       jsonb NOT NULL DEFAULT '[]'::jsonb
                        CHECK (jsonb_typeof(manifest_json) = 'array'),
    summary_json        jsonb NOT NULL DEFAULT '{}'::jsonb
                        CHECK (jsonb_typeof(summary_json) = 'object'),
    error_code          text,
    error_message       text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    started_at          timestamptz,
    completed_at        timestamptz,
    UNIQUE (device_id, idempotency_key),
    CHECK (completed_at IS NULL OR started_at IS NULL OR completed_at >= started_at)
);
CREATE INDEX idx_sync_changesets_device ON sync_changesets(device_id, created_at DESC);

CREATE TABLE sync_conflicts (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    changeset_id        uuid NOT NULL REFERENCES sync_changesets(id) ON DELETE CASCADE,
    relative_path       text NOT NULL,
    base_sha256         char(64)
                        CHECK (base_sha256 IS NULL OR base_sha256 ~ '^[a-f0-9]{64}$'),
    local_sha256        char(64)
                        CHECK (local_sha256 IS NULL OR local_sha256 ~ '^[a-f0-9]{64}$'),
    cloud_sha256        char(64)
                        CHECK (cloud_sha256 IS NULL OR cloud_sha256 ~ '^[a-f0-9]{64}$'),
    conflict_type       text NOT NULL CHECK (conflict_type IN ('both_modified', 'delete_modify', 'path_collision', 'binary_conflict', 'unknown')),
    status              text NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'resolved_local', 'resolved_cloud', 'resolved_merged', 'dismissed')),
    resolution_json     jsonb NOT NULL DEFAULT '{}'::jsonb
                        CHECK (jsonb_typeof(resolution_json) = 'object'),
    created_at          timestamptz NOT NULL DEFAULT now(),
    resolved_at         timestamptz,
    UNIQUE (changeset_id, relative_path)
);

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
    -- 存文本而不是 inet：审计只记录、不拿它做运算，
    -- 而 inet 在 JDBC 侧没有对应类型，得多带一个驱动依赖。
    ip_address      varchar(64),
    user_agent      text,
    details_json    jsonb NOT NULL DEFAULT '{}'::jsonb
                    CHECK (jsonb_typeof(details_json) = 'object')
);
CREATE INDEX idx_audit_events_time ON audit_events(occurred_at DESC);
CREATE INDEX idx_audit_events_target ON audit_events(target_type, target_id, occurred_at DESC);

-- ========== 回收站 ==========
CREATE TABLE skill_recycle_files (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    skill_id        uuid NOT NULL REFERENCES skills(id) ON DELETE CASCADE,
    path            text NOT NULL,
    sha256          char(64) NOT NULL
                    CHECK (sha256 ~ '^[a-f0-9]{64}$'),
    size_bytes      bigint NOT NULL DEFAULT 0,
    source_version  text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL,
    UNIQUE (skill_id, path)
);
CREATE INDEX idx_skill_recycle_files_skill ON skill_recycle_files(skill_id, expires_at);

-- 常用自动更新时间由应用层统一维护；也可用数据库触发器实现。
COMMIT;
