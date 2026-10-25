# 数据库 ER 图

SkillMesh 的数据库是 PostgreSQL 18，20 张表。表结构只有一份权威定义 —— Flyway 迁移
[`apps/api/src/main/resources/db/migrations/V1__init.sql`](../apps/api/src/main/resources/db/migrations/V1__init.sql)；
本文是它的图形化视图，**改了迁移脚本就得同步改这里**。

图按域拆成四张，避免一张图糊成一团。域之间靠 `skill_id` / `skill_version_id` / `device_id`
这几个外键接起来。

## 一、身份与组织

谁在用、属于哪些组织与团队。

```mermaid
erDiagram
    users {
        uuid id PK
        text oidc_issuer
        text oidc_subject
        text display_name
        text email
        text status "active|disabled|pending"
        timestamptz last_login_at
        timestamptz created_at
        timestamptz updated_at
    }
    organizations {
        uuid id PK
        text slug UK
        text name
        text status "active|suspended|archived"
        jsonb policy_json
        uuid created_by FK
        timestamptz created_at
        timestamptz updated_at
    }
    organization_memberships {
        uuid organization_id PK, FK
        uuid user_id PK, FK
        text role "owner|admin|publisher|reviewer|member|viewer"
        timestamptz joined_at
        uuid created_by FK
    }
    teams {
        uuid id PK
        uuid organization_id FK
        text slug
        text name
        text description
        text status "active|archived"
        timestamptz created_at
        timestamptz updated_at
    }
    team_memberships {
        uuid team_id PK, FK
        uuid user_id PK, FK
        text role "admin|publisher|reviewer|member|viewer"
        timestamptz joined_at
        uuid added_by FK
    }
    org_invitations {
        uuid id PK
        uuid organization_id FK
        uuid team_id FK
        text org_role "admin|publisher|reviewer|member|viewer"
        text team_role "admin|publisher|reviewer|member|viewer"
        char token_hash UK
        text status "pending|accepted|revoked|expired"
        uuid invited_by FK
        uuid accepted_by FK
        text subject
        timestamptz created_at
        timestamptz expires_at
        timestamptz accepted_at
    }

    users ||--o{ organization_memberships : "加入组织"
    users ||--o{ team_memberships : "加入团队"
    users ||--o{ org_invitations : "发出/接受邀请"
    users ||--o{ organizations : "创建"
    organizations ||--o{ organization_memberships : "成员"
    organizations ||--o{ teams : "下设团队"
    organizations ||--o{ org_invitations : "邀请进组织"
    teams ||--o{ team_memberships : "成员"
    teams ||--o{ org_invitations : "邀请进团队"
```

要点：

- `users` 按 `(oidc_issuer, oidc_subject)` 唯一 —— 同一个人在两个 IdP 下是两个账号。
- `organization_memberships` / `team_memberships` 是复合主键的多对多连接表。
- `org_invitations.org_role` **不含 `owner`**：owner 只能由现任 owner 直接任命，邀请给不了。

## 二、Skill 与制品

Skill 本体、来源、版本、制品与审核。

```mermaid
erDiagram
    skills {
        uuid id PK
        text slug
        text name
        text description
        uuid owner_user_id FK
        uuid organization_id FK
        uuid owner_team_id FK
        text visibility "private|team|organization|public"
        text status "active|archived|quarantined"
        text_array tags
        jsonb metadata_json
        text latest_version
        timestamptz created_at
        timestamptz updated_at
        timestamptz archived_at
    }
    skill_sources {
        uuid id PK
        uuid skill_id FK
        text source_type "local_import|git|registry|archive"
        text provider "github|gitlab|gitee|generic_git|local|skillmesh"
        text repository_url
        text subpath
        text ref_type
        text ref_value
        text resolved_commit
        text license_expression
        text license_status
        text credential_ref
        text source_fingerprint
        uuid created_by FK
        timestamptz created_at
        timestamptz updated_at
    }
    artifacts {
        uuid id PK
        char sha256 UK
        text storage_key UK
        text content_type
        bigint size_bytes
        int file_count
        jsonb manifest_json
        text signature
        text signature_key_id
        timestamptz created_at
    }
    skill_versions {
        uuid id PK
        uuid skill_id FK
        uuid source_id FK
        text version
        char base_revision
        text channel "stable|beta|dev"
        text source_commit
        uuid artifact_id FK
        text changelog
        jsonb manifest_json
        text status "draft|scanning|pending_review|published|rejected|revoked|yanked"
        uuid created_by FK
        uuid published_by FK
        timestamptz created_at
        timestamptz published_at
        timestamptz submitted_at
        timestamptz revoked_at
        text revoke_reason
    }
    skill_scans {
        uuid id PK
        uuid skill_version_id FK
        text scanner_name
        text scanner_version
        text status "passed|warning|failed|error"
        jsonb findings_json
        text summary
        timestamptz started_at
        timestamptz completed_at
    }
    skill_reviews {
        uuid id PK
        uuid skill_version_id FK
        uuid reviewer_id FK
        text decision "approved|rejected|changes_requested"
        text comment
        timestamptz created_at
    }
    skill_recycle_files {
        uuid id PK
        uuid skill_id FK
        text path
        char sha256
        bigint size_bytes
        text source_version
        timestamptz created_at
        timestamptz expires_at
    }

    skills ||--o{ skill_sources : "来源"
    skills ||--o{ skill_versions : "版本"
    skills ||--o{ skill_recycle_files : "回收站"
    skill_sources ||--o{ skill_versions : "产出版本"
    artifacts ||--o{ skill_versions : "制品"
    skill_versions ||--o{ skill_scans : "扫描"
    skill_versions ||--o{ skill_reviews : "审核"
```

要点：

- **`skill_versions` 是不可变载体**：`artifact_id`、`version`、`source_commit`、`manifest_json` 发布后不改；
  撤回靠 `status='revoked'` + `revoked_at`，改内容靠发新版本。
- `skills.latest_version` 与 `skill_versions (skill_id, version)` 之间有一条**延迟检查的外键**
  （`fk_skills_latest_version`，`DEFERRABLE INITIALLY DEFERRED`）：发版事务里先写版本行、
  后更新 `latest_version`，靠延迟到事务提交时才校验。
- **没有单独的权限表**：谁能看、谁能装、谁能发，由 Skill 的归属与 `visibility`，
  加上 `organization_memberships` / `team_memberships` 里的角色推导出来。
- `skill_recycle_files` 只存元数据：文件内容还在旧版本的制品里，恢复时从那里取字节重打一版。

## 三、设备、安装与同步

客户端在本机装了什么、以及云端与本机的对账。

```mermaid
erDiagram
    devices {
        uuid id PK
        uuid user_id FK
        text device_key
        text display_name
        text platform
        text cli_version
        timestamptz last_seen_at
        timestamptz revoked_at
        timestamptz created_at
    }
    installations {
        uuid id PK
        uuid user_id FK
        uuid device_id FK
        uuid skill_id FK
        uuid skill_version_id FK
        uuid source_id FK
        text agent "claude-code|codex|cursor|generic"
        text install_scope "user|project|custom"
        text project_root
        text target_path
        text installed_version
        text installed_commit
        char installed_sha256
        text update_policy "manual|notify|auto_safe"
        bool local_modified
        text status "active|missing|conflict|uninstalled|blocked"
        timestamptz installed_at
        timestamptz last_checked_at
        timestamptz last_updated_at
    }
    sync_states {
        uuid id PK
        uuid device_id FK
        uuid skill_id FK
        text local_path
        text local_revision
        text cloud_revision
        text base_revision
        char local_manifest_hash
        char cloud_manifest_hash
        text status "in_sync|local_ahead|cloud_ahead|conflict|paused|error"
        timestamptz last_synced_at
        text last_error_code
        text last_error_message
        timestamptz updated_at
    }
    sync_changesets {
        uuid id PK
        uuid device_id FK
        uuid skill_id FK
        text idempotency_key
        text direction "upload|download|bidirectional"
        text base_revision
        text target_revision
        text status "queued|processing|completed|conflict|rejected|failed|cancelled"
        jsonb manifest_json
        jsonb summary_json
        text error_code
        text error_message
        timestamptz created_at
        timestamptz started_at
        timestamptz completed_at
    }
    sync_conflicts {
        uuid id PK
        uuid changeset_id FK
        text relative_path
        char base_sha256
        char local_sha256
        char cloud_sha256
        text conflict_type "both_modified|delete_modify|path_collision|binary_conflict|unknown"
        text status "open|resolved_local|resolved_cloud|resolved_merged|dismissed"
        jsonb resolution_json
        timestamptz created_at
        timestamptz resolved_at
    }
    upload_sessions {
        uuid id PK
        uuid actor_user_id FK
        uuid device_id FK
        text purpose "skill_artifact|sync_file"
        uuid skill_version_id FK
        uuid changeset_id FK
        text relative_path
        char expected_sha256
        bigint expected_size_bytes
        text temporary_storage_key UK
        text status "created|uploaded|verifying|completed|failed|expired"
        timestamptz expires_at
        timestamptz uploaded_at
        timestamptz verified_at
        text error_code
        timestamptz created_at
    }

    users ||--o{ devices : "注册设备"
    users ||--o{ installations : "安装"
    devices ||--o{ installations : "安装"
    devices ||--o{ sync_states : "本机状态"
    devices ||--o{ sync_changesets : "提交变更集"
    devices ||--o{ upload_sessions : "上传"
    skills ||--o{ installations : "被安装"
    skills ||--o{ sync_states : "同步"
    skills ||--o{ sync_changesets : "同步"
    skill_versions ||--o{ installations : "装的是这版"
    skill_versions ||--o{ upload_sessions : "上传的制品"
    skill_sources ||--o{ installations : "来自"
    sync_changesets ||--o{ sync_conflicts : "冲突"
    sync_changesets ||--o{ upload_sessions : "同步文件上传"
```

要点：

- `sync_states` 一行 = 一个设备上一个 Skill 的一个本地路径（三列复合唯一），存**本地与云端两份修订号**
  和两份清单哈希，`status` 就是对账结论。
- `sync_changesets.idempotency_key` + `device_id` 唯一：同一台设备重复提交同一次变更只会有一行。
- `upload_sessions` 分两种用途（`purpose`）：`skill_artifact` 指向 `skill_version_id`，
  `sync_file` 指向 `changeset_id` + `relative_path` —— 这条互斥关系由 CHECK 约束保证。
- 上传走「先写临时对象 → 校验哈希 → 落库 → 异步清孤儿」，所以对象存储里会有短期孤儿，
  由 [清理桶里的孤儿对象](部署与排障.md#清理桶里的孤儿对象) 一节收尾。

## 四、审计

谁在什么时候对什么做了什么。

```mermaid
erDiagram
    audit_events {
        bigserial id PK
        timestamptz occurred_at
        uuid actor_user_id FK
        uuid organization_id FK
        uuid device_id FK
        text action
        text target_type
        text target_id
        text outcome "success|denied|failure"
        text request_id
        varchar(64) ip_address
        text user_agent
        jsonb details_json
    }

    users ||--o{ audit_events : "操作人"
    organizations ||--o{ audit_events : "所属组织"
    devices ||--o{ audit_events : "来自设备"
```

要点：

- `target_type` + `target_id` 是多态引用，指向任意业务对象，**没有外键**（审计表不能因为业务对象被删就跟着删）。
- `actor_user_id` / `organization_id` / `device_id` 都是 `ON DELETE SET NULL` —— 人或设备注销了，审计流水还在。

## 关系总览

```mermaid
flowchart LR
    subgraph 身份与组织
        U[users]
        O[organizations]
        T[teams]
        I[org_invitations]
    end
    subgraph Skill 与制品
        S[skills]
        V[skill_versions]
        A[artifacts]
        R[skill_recycle_files]
    end
    subgraph 同步与安装
        D[devices]
        IN[installations]
        SS[sync_states]
        CS[sync_changesets]
        US[upload_sessions]
    end
    subgraph 审计
        AE[audit_events]
    end

    U --> O
    U --> T
    O --> I
    U --> S
    O --> S
    T --> S
    S --> V
    V --> A
    S --> R
    U --> D
    D --> IN
    D --> SS
    D --> CS
    S --> IN
    S --> SS
    S --> CS
    V --> US
    CS --> US
    U --> AE
    O --> AE
    D --> AE
```

## 维护

改 schema 时同步更新本文的两处：

1. 上面的 Mermaid 图（表、列、外键）
2. [详细设计 §1.3](详细设计.md#13-ddl-落地说明) 里与该表相关的落地约束

字段类型与约束的权威来源始终是 `V1__init.sql`；本图只保证「关系与关键约束」与之一致。
