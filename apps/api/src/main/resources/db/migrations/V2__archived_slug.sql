-- 归档（软删除）的 Skill 不再占用 slug。
--
-- 原来 slug 在个人空间与组织空间里全局唯一，删掉的 Skill 也占着坑：
-- 归档之后既不能还原（撞唯一索引），也不能用同一个名字重建。
-- 改成只约束 active：归档的让出 slug，还原时若已有同名的 active，
-- 服务端会报 slug 冲突让人先处理（见 SkillService.restore）。
DROP INDEX IF EXISTS uq_skills_personal_slug;
CREATE UNIQUE INDEX uq_skills_personal_slug
    ON skills(owner_user_id, slug)
    WHERE organization_id IS NULL AND owner_user_id IS NOT NULL AND status = 'active';

DROP INDEX IF EXISTS uq_skills_org_slug;
CREATE UNIQUE INDEX uq_skills_org_slug
    ON skills(organization_id, slug)
    WHERE organization_id IS NOT NULL AND status = 'active';
