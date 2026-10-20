-- v2：团队控制台——邀请成员进组织/团队。

CREATE TABLE org_invitations (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    team_id         uuid REFERENCES teams(id) ON DELETE CASCADE,
    org_role        text NOT NULL DEFAULT 'member'
                    CHECK (org_role IN ('owner', 'admin', 'publisher', 'reviewer', 'member', 'viewer')),
    team_role       text NOT NULL DEFAULT 'member'
                    CHECK (team_role IN ('admin', 'publisher', 'reviewer', 'member', 'viewer')),
    token_hash      char(64) NOT NULL UNIQUE,
    status          text NOT NULL DEFAULT 'pending'
                    CHECK (status IN ('pending', 'accepted', 'revoked', 'expired')),
    invited_by      uuid REFERENCES users(id) ON DELETE SET NULL,
    accepted_by     uuid REFERENCES users(id) ON DELETE SET NULL,
    subject         text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL,
    accepted_at     timestamptz
);
CREATE INDEX idx_org_invitations_org ON org_invitations(organization_id, status);
