-- v2：审计流水按 Skill 查时，join 条件是 skills.id::text = audit_events.target_id。
-- skills.id 是 uuid、audit_events.target_id 是 text，转成 text 之后走不了 skills
-- 的主键索引——这条 join 只能把 skills 整表扫一遍。
-- 给 skills 加一个表达式索引，这条 join 才能走索引扫描。

CREATE INDEX idx_skills_id_text ON skills ((id::text));
