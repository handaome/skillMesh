-- 内置账号：用户名 + 密码登录，不依赖外部 IdP。
--
-- oidc_issuer / oidc_subject 改成可空：内置账号没有 OIDC 身份，
-- 走外部 IdP 登录的账号照旧填两列。
--
-- 原来的 UNIQUE (oidc_issuer, oidc_subject) 约束保留：
-- PostgreSQL 的 UNIQUE 允许多行都是 NULL，内置账号互不冲突。

BEGIN;

ALTER TABLE users ALTER COLUMN oidc_issuer DROP NOT NULL;
ALTER TABLE users ALTER COLUMN oidc_subject DROP NOT NULL;

ALTER TABLE users ADD COLUMN username text;
ALTER TABLE users ADD COLUMN password_hash text;
ALTER TABLE users ADD COLUMN role text NOT NULL DEFAULT 'user'
    CHECK (role IN ('admin', 'user'));

-- 用户名唯一且大小写不敏感：登录时不区分大小写。
-- 部分索引：OIDC 账号的 username 是 NULL，不参与唯一性。
CREATE UNIQUE INDEX users_username_unique
    ON users (lower(username)) WHERE username IS NOT NULL;

COMMIT;
