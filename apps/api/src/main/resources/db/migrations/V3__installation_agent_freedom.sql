-- installations.agent 不再限定枚举值。
--
-- 原来是 CHECK (agent IN ('claude-code','codex','cursor','generic'))：
-- 自定义 Agent 装完登记不上，服务端报 400，而 CLI 那时把这个 400 吞了——
-- 装完看着成功，跨设备却看不到这条记录。
--
-- Agent 目录是本机配置的事（config.yaml 的 agents 段），服务端不该替用户
-- 限定有哪些 Agent。放开取值，但仍要挡住空串与不像名字的值。
ALTER TABLE installations DROP CONSTRAINT IF EXISTS installations_agent_check;

ALTER TABLE installations ADD CONSTRAINT installations_agent_check
    CHECK (length(btrim(agent)) > 0 AND length(agent) <= 64);
