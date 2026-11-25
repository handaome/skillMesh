package com.skillmesh.api.version;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 发布结论落到版本上：状态，以及状态该带的时间戳（设计文档 §10.1）。
 *
 * 整包上传与同步提交共用这一份规则，三条分支都要对。
 */
class VersionStatusResolverTest {

    private static final UUID PUBLISHER = UUID.randomUUID();
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-01-02T03:04:05Z");

    @Test
    void 发布结论决定状态与时间戳() {
        SkillVersionEntity blocked = new SkillVersionEntity();
        assertThat(VersionStatusResolver.applyPublishDecision(blocked, true, false, PUBLISHER, NOW))
            .isEqualTo(VersionStatus.quarantined);
        assertThat(blocked.getPublishedAt()).isNull();
        assertThat(blocked.getSubmittedAt()).isNull();

        SkillVersionEntity waiting = new SkillVersionEntity();
        assertThat(VersionStatusResolver.applyPublishDecision(waiting, false, true, PUBLISHER, NOW))
            .isEqualTo(VersionStatus.pending_review);
        assertThat(waiting.getSubmittedAt()).isEqualTo(NOW);
        assertThat(waiting.getPublishedAt()).isNull();

        SkillVersionEntity published = new SkillVersionEntity();
        assertThat(VersionStatusResolver.applyPublishDecision(published, false, false, PUBLISHER, NOW))
            .isEqualTo(VersionStatus.published);
        assertThat(published.getPublishedBy()).isEqualTo(PUBLISHER);
        assertThat(published.getPublishedAt()).isEqualTo(NOW);
        assertThat(published.getSubmittedAt()).isNull();
    }
}
