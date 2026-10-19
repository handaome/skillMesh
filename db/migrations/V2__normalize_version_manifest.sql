-- manifest_json 一直是数组语义（DDL 默认 '[]'），但早期版本创建时把它写成
-- 空对象 {}，Hibernate 按 List 读会直接抛
-- "Could not deserialize string to java type: java.util.List"。
-- 统一成空数组：这些行的清单本来就没落过，不丢信息。
UPDATE skill_versions
SET manifest_json = '[]'::jsonb
WHERE jsonb_typeof(manifest_json) = 'object';

UPDATE artifacts
SET manifest_json = '[]'::jsonb
WHERE jsonb_typeof(manifest_json) = 'object';
