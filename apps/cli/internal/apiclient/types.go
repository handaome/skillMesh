package apiclient

// 与 api-spec/openapi.yaml 的 components/schemas 对应。字段名保持 snake_case，
// 不额外加 struct tag —— 契约用 snake_case，Go 结构体同形可读性更好。

type Skill struct {
	ID            string   `json:"id"`
	Slug          string   `json:"slug"`
	Name          string   `json:"name"`
	Description   string   `json:"description"`
	Visibility    string   `json:"visibility"`
	Status        string   `json:"status"`
	Tags          []string `json:"tags"`
	LatestVersion *string  `json:"latest_version"`
	CreatedAt     string   `json:"created_at"`
	UpdatedAt     string   `json:"updated_at"`
}

type SkillPage struct {
	Items      []Skill `json:"items"`
	NextCursor *string `json:"next_cursor"`
	HasMore    bool    `json:"has_more"`
}

type ArtifactSummary struct {
	ID          string `json:"id"`
	SHA256      string `json:"sha256"`
	SizeBytes   int64  `json:"size_bytes"`
	ContentType string `json:"content_type"`
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
	PublishedAt  *string          `json:"published_at"`
	CreatedAt    string           `json:"created_at"`
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
	} `json:"user"`
	Organizations []struct {
		ID   string `json:"id"`
		Slug string `json:"slug"`
		Name string `json:"name"`
		Role string `json:"role"`
	} `json:"organizations"`
}
