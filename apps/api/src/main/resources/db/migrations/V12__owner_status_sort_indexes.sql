-- 补上两条「等值条件 + 排序」都有的路径，V6 那三个索引对不上。
--
-- V6 的注释说「索引都把状态列放在最前面：这三处查询都带 status 的等值条件」——
-- 其实不是。逐条对：
--
-- 1. idx_skills_status_updated (status, updated_at desc, id desc)
--    服务全局列表与搜索：过滤 status='active'，排序 (updated_at, id)，
--    等值列在前、游标比较的列与索引一致 ✓
--
-- 2. idx_skill_versions_review_queue (status, submitted_at desc nulls last, ...)
--    服务待审队列：过滤 status='pending_review'，排序三级与游标三级一致 ✓
--
-- 3. idx_installations_user_updated (user_id, last_updated_at desc)
--    服务安装列表「不按状态过滤」那条；但按状态过滤时走的是
--    (user_id, status) 的等值条件，索引里没有 status 列，
--    只能出顺序再逐行判状态——本文件的第三条补上它。
--
-- 另外，归档列表（findArchived）过滤的是 owner_user_id + status，
-- V6 的 idx_skills_status_updated 没有 owner 列，属主过滤落在索引外面。
-- 本文件的第一条补上它。
--
-- 索引都写成可重入的：迁移历史被手工删过时，重跑不会撞 42P07。

-- 归档列表：where owner_user_id = ? and status = 'archived'
--   order by updated_at desc, id desc（游标比较的也是这两列）
CREATE INDEX IF NOT EXISTS idx_skills_owner_status_updated
    ON skills(owner_user_id, status, updated_at DESC, id DESC);

-- 安装列表（按状态过滤）：where user_id = ? and status = ?
--   order by last_updated_at desc
CREATE INDEX IF NOT EXISTS idx_installations_user_status_updated
    ON installations(user_id, status, last_updated_at DESC);
