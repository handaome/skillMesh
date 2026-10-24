-- v10：文件级回收站。
--
-- 设计文档 §6.4：删除传播走「软删除、回收期、支持恢复」。
-- 一版里少掉的文件不是消失了：它进了回收站，回收期内能找回来。
--
-- 只记元数据：文件内容还在旧版本的制品里（版本不可变，制品不会被删），
-- 恢复时从那里取字节重打一版，不必再存一份。

CREATE TABLE skill_recycle_files (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    skill_id        uuid NOT NULL REFERENCES skills(id) ON DELETE CASCADE,
    path            text NOT NULL,
    sha256          char(64) NOT NULL,
    size_bytes      bigint NOT NULL DEFAULT 0,
    source_version  text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL,
    UNIQUE (skill_id, path)
);
CREATE INDEX idx_skill_recycle_files_skill ON skill_recycle_files(skill_id, expires_at);
