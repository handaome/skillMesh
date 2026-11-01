-- 版本状态里的 scanning 删掉。
--
-- 扫描在转正的同一个事务里同步做完，没有可观察的中间态——
-- 这一档从建立起就没人到过（设计文档 §10.1）。
-- 留着它，接口与词表里就有一个永远不出现的取值：客户端照它写分支
-- （显示「扫描中」），服务端永远不回它。
--
-- 约束跟着改：删掉旧约束，按新的取值集合重建。
-- 行数据里不该有 scanning（那一档不可达），真有人手工塞进去过，
-- 先清掉再建约束——不然建约束会失败。
DELETE FROM skill_versions WHERE status = 'scanning';

ALTER TABLE skill_versions DROP CONSTRAINT IF EXISTS skill_versions_status_check;
ALTER TABLE skill_versions ADD CONSTRAINT skill_versions_status_check
    CHECK (status IN ('draft', 'pending_review', 'published', 'rejected',
                       'quarantined', 'revoked', 'yanked'));
