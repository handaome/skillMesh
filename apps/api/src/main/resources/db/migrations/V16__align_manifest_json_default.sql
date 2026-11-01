-- skill_versions.manifest_json 的默认值跟 V1 声明的对不上（设计文档 §1.2）。
--
-- 现场是这样：skill_mesh_local 库里这一列的默认值是 '{}'::jsonb，
-- 而 V1__init.sql 写的是 '[]'::jsonb。列上还挂着
-- CHECK (jsonb_typeof(manifest_json) = 'array')，于是默认值本身违反约束：
-- 任何不带 manifest_json 的 INSERT 都会报
-- "violates check constraint skill_versions_manifest_json_check"，
-- 报错信息完全指不到真正的原因。
--
-- 早先这个库不是 Flyway 建出来的，V1 改过之后又 repair 过，
-- 改默认值这件事没跟着走。应用本身不受影响——Hibernate 每次都显式写这一列，
-- 但库里的默认值摆在那里就是个雷：将来有人写原生 SQL 插版本，直接踩雷。
--
-- 一次性对齐成 V1 声明的样子。新建的库跑这条是空操作。

ALTER TABLE skill_versions
    ALTER COLUMN manifest_json SET DEFAULT '[]'::jsonb;
