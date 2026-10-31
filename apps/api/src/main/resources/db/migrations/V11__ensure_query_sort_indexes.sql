-- 三个排序键的索引，再补一遍。
--
-- V6 建的就是这三个索引，但它已经在别的库上应用过了，文件内容不能改
-- （改了校验和就对不上，应用起不来）。老库上于是有两种情况：
--   - Flyway 认为 V6 已经跑过（历史表里那行被 repair 对齐了），索引其实没建；
--   - 索引建好了，但迁移历史被手工删过，V6 要重跑。
-- IF NOT EXISTS 把两种情况收敛到同一个结果：建过就跳过，没建就建上。
--
-- 索引名是这轮新起的，旧链的 V1~V10 里都没有同名索引，
-- 不会出现「同名不同定义」被静默跳过的情况。
--
-- 三处的排序键：
--   技能列表 / 搜索  order by s.updated_at desc, s.id desc
--     过滤条件 s.status = 'active'，状态放开头：等值条件定位到一段区间，
--     后面直接出顺序，游标比较 (updated_at, id) 才能当索引边界用。
--     「我归档的」按 owner_user_id 过滤，走现有的
--     idx_skills_owner_user (owner_user_id, updated_at desc)。
--   待审队列         order by v.submitted_at desc nulls last, v.created_at desc, v.id desc
--     游标比较的也是这三级，索引列与排序完全一致才能直接翻页。
--     NULLS LAST 要写进索引定义：PostgreSQL 的 DESC 默认是 NULLS FIRST，
--     与查询里的 nulls last 对不上就用不上这个索引的顺序。
--   安装列表         order by last_updated_at desc
--     这一处只按 user_id 过滤、不带 status，状态放开头用不上；
--     现有的 idx_installations_user (user_id, status) 也不带排序键，还得再排一遍。
CREATE INDEX IF NOT EXISTS idx_skills_status_updated
    ON skills(status, updated_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_skill_versions_review_queue
    ON skill_versions(status, submitted_at DESC NULLS LAST, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_installations_user_updated
    ON installations(user_id, last_updated_at DESC);
