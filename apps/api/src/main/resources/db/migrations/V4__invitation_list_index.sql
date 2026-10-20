-- v2：邀请列表索引改成跟查询对上。
--
-- 唯一按组织查邀请的查询是 findByOrganizationIdOrderByCreatedAtDesc：
-- 只按 organization_id 过滤、按 created_at DESC 排序，从不过滤 status。
-- 原来的索引 (organization_id, status) 第二列用不上，排序也没被覆盖。
-- V3 已经应用过，不能改文件（Flyway 会因校验和不一致而失败），所以另起一条迁移。

DROP INDEX idx_org_invitations_org;
CREATE INDEX idx_org_invitations_org ON org_invitations(organization_id, created_at DESC);
