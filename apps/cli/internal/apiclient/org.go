package apiclient

import (
	"context"
	"net/http"
	"net/url"
)

// OrgMembership 是我在一个组织里的身份（来自 GET /me）。
type OrgMembership struct {
	ID   string `json:"id"`
	Slug string `json:"slug"`
	Name string `json:"name"`
	Role string `json:"role"`
}

// Team 是组织下的一个团队。
type Team struct {
	ID             string `json:"id"`
	OrganizationID string `json:"organization_id"`
	Slug           string `json:"slug"`
	Name           string `json:"name"`
	Status         string `json:"status"`
}

// Teams 列组织下的团队。
func (c *Client) Teams(ctx context.Context, organizationID string) ([]Team, error) {
	var out []Team
	path := "/organizations/" + url.PathEscape(organizationID) + "/teams"
	if err := c.do(ctx, http.MethodGet, path, nil, &out); err != nil {
		return nil, err
	}
	return out, nil
}
