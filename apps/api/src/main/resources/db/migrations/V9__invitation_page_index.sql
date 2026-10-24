-- v9：邀请分页的索引跟上排序键。
--
-- V4 建的索引是 (organization_id, created_at DESC)，分页的排序键多了 id：
-- 游标是 (created_at, id) 复合键，created_at 会重复（同一秒建的邀请），
-- 单靠它翻页会重不漏不了。PG 只能对多出来的 id 做增量排序。
--
-- 索引补上 id 就跟排序键完全一致，翻页不用再排。

DROP INDEX idx_org_invitations_org;
CREATE INDEX idx_org_invitations_org ON org_invitations(organization_id, created_at DESC, id DESC);
