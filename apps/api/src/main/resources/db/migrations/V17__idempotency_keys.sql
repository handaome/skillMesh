-- 幂等键（详细设计 §2.1「可重试写请求：支持 Idempotency-Key」、§4.3 API-001）。
--
-- 客户端重试写请求时带同一个 Idempotency-Key：服务端认得出「这是同一次请求」，
-- 把上次的响应原样还回去，不再建一份资源。
--
-- 为什么单独一张表而不是给每张业务表加一列：键的生命周期跟着「请求」走，
-- 不是跟着「资源」走。业务表加列的话，每个建资源的接口都要加一遍、
-- 都要写一遍「先查后建」的逻辑——漏一个接口就漏一处重复。
--
-- 唯一键是 (user_id, scope, idem_key)：
-- - user_id：同一个键在不同用户之间不互相干扰；
-- - scope：接口 + 路径参数，不同接口各认各的；
-- - idem_key：客户端给的键。
--
-- response_body 为空 = 认领了但还没做完（请求还在处理中，或者上次失败了——
-- 失败时服务层会把这行删掉，让重试能再来一次）。

CREATE TABLE idempotency_keys (
    user_id        uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    scope          text NOT NULL,
    idem_key       text NOT NULL,
    -- 请求体的摘要：同一个键配了不同的内容就是用错了键，要报冲突而不是照做。
    request_digest text NOT NULL,
    -- 上次的响应体，原样存下来给重试用。
    response_body  jsonb,
    created_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, scope, idem_key)
);

COMMENT ON TABLE idempotency_keys IS '幂等键：同一个键重复提交返回上次的响应，不重复建资源';
COMMENT ON COLUMN idempotency_keys.scope IS '接口作用域，例如 POST /skills';
COMMENT ON COLUMN idempotency_keys.response_body IS '上次的响应体；为空表示认领了还没做完';
