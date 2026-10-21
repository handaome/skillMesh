-- v2：待审队列的 submitted_at 要是真的送审时间，不是建版本的时间。
-- 建版本（draft）与上传制品可能隔半天，队列里显示的时间得是进队列的那一刻。

ALTER TABLE skill_versions ADD COLUMN submitted_at timestamptz;
