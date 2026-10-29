package apiclient

// 与 api-spec/openapi.yaml 的 components/schemas 对应。字段名保持 snake_case，
// 不额外加 struct tag —— 契约用 snake_case，Go 结构体同形可读性更好。

type Skill struct {
	ID          string `json:"id"`
	Slug        string `json:"slug"`
	Name        string `json:"name"`
	Description string `json:"description"`
	Visibility  string `json:"visibility"`
	Status      string `json:"status"`
	// OrganizationID 与 OwnerTeamID 是 Skill 的归属，空串表示个人 Skill。
	// 服务端一直在返回这两个字段，客户端之前没接——「已有的 Skill 属于谁」就比不出来，
	// 于是发 v2 时只能把「带 scope」一律当成「要改归属」拦掉。
	OrganizationID string   `json:"organization_id"`
	OwnerTeamID    string   `json:"owner_team_id"`
	Tags           []string `json:"tags"`
	LatestVersion  *string  `json:"latest_version"`
	CreatedAt      string   `json:"created_at"`
	UpdatedAt      string   `json:"updated_at"`
}

// SkillSource 是随创建请求带上的来源信息（设计文档 §7.1）。
type SkillSource struct {
	SourceType        string `json:"source_type"`
	Provider          string `json:"provider"`
	RepositoryURL     string `json:"repository_url,omitempty"`
	Subpath           string `json:"subpath,omitempty"`
	RefType           string `json:"ref_type,omitempty"`
	RefValue          string `json:"ref_value,omitempty"`
	ResolvedCommit    string `json:"resolved_commit,omitempty"`
	LicenseExpression string `json:"license_expression,omitempty"`
	LicenseStatus     string `json:"license_status,omitempty"`
}

type SkillPage struct {
	Items      []Skill `json:"items"`
	NextCursor *string `json:"next_cursor"`
	HasMore    bool    `json:"has_more"`
}

type ArtifactSummary struct {
	ID             string `json:"id"`
	SHA256         string `json:"sha256"`
	SizeBytes      int64  `json:"size_bytes"`
	ContentType    string `json:"content_type"`
	Signature      string `json:"signature"`
	SignatureKeyID string `json:"signature_key_id"`
	ContentDigest  string `json:"content_digest"`
}

type SkillVersion struct {
	ID           string           `json:"id"`
	SkillID      string           `json:"skill_id"`
	Version      string           `json:"version"`
	Channel      string           `json:"channel"`
	Status       string           `json:"status"`
	SourceCommit *string          `json:"source_commit"`
	Artifact     *ArtifactSummary `json:"artifact"`
	Manifest     []ManifestEntry  `json:"manifest"`
	Scan         *ScanSummary     `json:"scan"`
	PublishedAt  *string          `json:"published_at"`
	CreatedAt    string           `json:"created_at"`
}

// ScanSummary 是服务端对这一份制品的扫描结论。
type ScanSummary struct {
	ScannerName    string        `json:"scanner_name"`
	ScannerVersion string        `json:"scanner_version"`
	Status         string        `json:"status"`
	Summary        string        `json:"summary"`
	CompletedAt    *string       `json:"completed_at"`
	Findings       []ScanFinding `json:"findings"`
}

type ScanFinding struct {
	Path   string `json:"path"`
	Line   int    `json:"line"`
	Rule   string `json:"rule"`
	Detail string `json:"detail"`
}

type VersionPage struct {
	Items      []SkillVersion `json:"items"`
	NextCursor *string        `json:"next_cursor"`
	HasMore    bool           `json:"has_more"`
}

type MeResponse struct {
	User struct {
		ID          string `json:"id"`
		DisplayName string `json:"display_name"`
		Email       string `json:"email"`
		Status      string `json:"status"`
		// Subject 是外部 IdP 签的 sub；内置账号下就是用户名。
		Subject string `json:"subject"`
	} `json:"user"`
	Organizations []OrgMembership `json:"organizations"`
}
