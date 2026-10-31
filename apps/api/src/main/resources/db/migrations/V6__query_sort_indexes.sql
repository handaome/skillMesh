-- 排序键的索引：数据量上来后，这三处会退化成全表扫 + 排序。
--
-- 索引都把状态列放在最前面：这三处查询都带 status 的等值条件
-- （active / pending_review），等值列放开头，索引既当过滤用又能直接出顺序，
-- 不用回表再排一遍。
--
-- 已有索引覆盖不到全局列表：idx_skills_owner_user (owner_user_id, updated_at DESC)
-- 服务的是「我的 Skill」那一条路径（owner 前缀），
-- 不限属主的全局列表用不上它。

-- 技能列表 / 搜索：order by s.updated_at desc, s.id desc
-- 归档列表（findArchived）也走这个顺序，只是 status 换成 archived。
CREATE INDEX idx_skills_status_updated ON skills(status, updated_at DESC, id DESC);

-- 待审队列：order by v.submitted_at desc nulls last, v.created_at desc, v.id desc
-- 游标比较的也是这三级，索引列与排序完全一致才能直接翻页。
-- NULLS LAST 要写进索引定义里：PostgreSQL 的 DESC 默认是 NULLS FIRST，
-- 与查询里的 nulls last 对不上就用不上这个索引的顺序。
CREATE INDEX idx_skill_versions_review_queue
    ON skill_versions(status, submitted_at DESC NULLS LAST, created_at DESC, id DESC);

-- 安装列表：order by last_updated_at desc
CREATE INDEX idx_installations_user_updated ON installations(user_id, last_updated_at DESC);
