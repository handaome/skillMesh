-- 技能集合：命名的一组 Skill。
--
-- 集合的成员是 Skill（每个 Skill 的可见性由它自己管），不是设备安装记录——
-- 集合只是把「哪些 Skill 该一起用」记下来，不引入新的可见性口径，
-- 也就不会变成一个泄露「谁在哪些机器上装了什么」的入口。
--
-- 成员存成 uuid 数组而不是一张成员表：成员关系只有「属于哪个集合」，
-- 没有别的属性；集合本身通常只有几个成员，数组足够，也省一张表。
CREATE TABLE skill_groups (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    slug              text NOT NULL,
    name              text NOT NULL,
    description       text NOT NULL DEFAULT '',
    owner_user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    organization_id   uuid REFERENCES organizations(id) ON DELETE CASCADE,
    owner_team_id     uuid REFERENCES teams(id) ON DELETE CASCADE,
    visibility        text NOT NULL
                      CHECK (visibility IN ('private', 'team', 'organization', 'public')),
    status            text NOT NULL DEFAULT 'active'
                      CHECK (status IN ('active', 'archived')),
    member_ids        uuid[] NOT NULL DEFAULT '{}',
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now()
);

-- 与 skills 同一个口径：归档的集合让出 slug，可以重建一个同名的。
CREATE UNIQUE INDEX uq_skill_groups_personal_slug
    ON skill_groups(owner_user_id, slug)
    WHERE organization_id IS NULL AND status = 'active';

CREATE UNIQUE INDEX uq_skill_groups_org_slug
    ON skill_groups(organization_id, slug)
    WHERE organization_id IS NOT NULL AND status = 'active';

CREATE INDEX idx_skill_groups_owner ON skill_groups(owner_user_id, status);
-- 「这个 Skill 属于哪些集合」：集合详情页要反查。
CREATE INDEX idx_skill_groups_members ON skill_groups USING gin(member_ids);
