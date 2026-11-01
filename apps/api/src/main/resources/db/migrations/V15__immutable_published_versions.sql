-- 已发布版本的内容不可原位替换（设计文档 §1.5、§4.3 DB-002）。
--
-- 为什么要有这一层：服务层已经只做「建新版本」，不改旧版本，
-- 但那是约定不是约束——将来有人写了个「就地修一下版本」的路径，
-- 已经发布的制品就被换掉了，而 clients 手上还拿着旧摘要。
-- 数据库触发器是最后一道：它不认业务意图，只认字段变没变。
--
-- 只挡内容字段，状态字段照常能改：撤回（revoked）与下架（yanked）
-- 改的正是 status，发布后仍然允许变。

CREATE OR REPLACE FUNCTION prevent_published_skill_version_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status IN ('published', 'revoked', 'yanked') THEN
        IF NEW.skill_id IS DISTINCT FROM OLD.skill_id
           OR NEW.version IS DISTINCT FROM OLD.version
           OR NEW.artifact_id IS DISTINCT FROM OLD.artifact_id
           OR NEW.source_commit IS DISTINCT FROM OLD.source_commit
           OR NEW.manifest_json IS DISTINCT FROM OLD.manifest_json THEN
            RAISE EXCEPTION 'published skill version content is immutable';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_skill_version_immutable
BEFORE UPDATE ON skill_versions
FOR EACH ROW EXECUTE FUNCTION prevent_published_skill_version_mutation();
