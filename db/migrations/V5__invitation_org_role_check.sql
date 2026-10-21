-- v2：邀请表的 org_role 约束收紧到「邀请能授」的那几档。
--
-- V3 的 CHECK 抄的是组织角色全集（含 owner），不是邀请可授的角色集：
-- owner 只能由现任 owner 直接任命，邀请给不了（Roles.INVITABLE_ORG_ROLES）。
-- 应用层有三道闸（DTO @Pattern、服务层校验、接受时 requireOwnerToGrantOwner），
-- 所以提不了权；只是数据库没承载这条不变量——本该永远不可能存在的
-- 一条 owner 邀请能静悄悄躺在表里。
--
-- V3 已经应用过，不能改文件（Flyway 会因校验和变化而失败），所以另起一条迁移。

-- 应用层写不出 org_role='owner'，真有的话也是脏数据：
-- 留着下面加约束就会失败，先清掉。
DELETE FROM org_invitations WHERE org_role = 'owner';

ALTER TABLE org_invitations DROP CONSTRAINT org_invitations_org_role_check;
ALTER TABLE org_invitations ADD CONSTRAINT org_invitations_org_role_check
    CHECK (org_role IN ('admin', 'publisher', 'reviewer', 'member', 'viewer'));
