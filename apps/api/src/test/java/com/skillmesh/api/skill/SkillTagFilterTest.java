package com.skillmesh.api.skill;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.auth.UserEntity;
import com.skillmesh.api.auth.UserRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 标签筛选的两件事：标签清单要对得上可见范围，「未打标」要真的筛出没标签的。
 *
 * 标签清单要是按「全部 Skill」统计，就会把别人私有的标签也报出来——
 * 标签名本身就是信息（能看出这个团队在做什么），可见范围不能漏。
 */
@SpringBootTest
@Transactional
class SkillTagFilterTest {

    @Autowired
    private SkillRepository skills;

    @Autowired
    private UserRepository users;

    private CurrentUser owner;
    private CurrentUser other;

    private UUID alphaOnly;
    private UUID alphaBeta;
    private UUID noTag;
    private UUID otherPrivate;

    @BeforeEach
    void setUp() {
        owner = newUser("属主");
        other = newUser("别人");
        alphaOnly = newSkill(owner, "alpha-only", "public", new String[] {"alpha"});
        alphaBeta = newSkill(owner, "alpha-beta", "public", new String[] {"alpha", "beta"});
        noTag = newSkill(owner, "no-tag", "public", new String[0]);
        otherPrivate = newSkill(other, "other-private", "private", new String[] {"alpha"});
    }

    @Test
    void 标签清单只统计看得见的() {
        Map<String, Long> counts = countByTag(skills.visibleTagCounts(owner.id(), false));
        // 别人的私有 Skill 也带 alpha，但不能算进来。
        assertThat(counts.get("alpha")).isEqualTo(2L);
        assertThat(counts.get("beta")).isEqualTo(1L);
        assertThat(counts).doesNotContainKey("gamma");
    }

    @Test
    void 只看自己的时按属主过滤() {
        Map<String, Long> counts = countByTag(skills.visibleTagCounts(other.id(), true));
        // mine=true 时只有自己的：别人的 alpha 不算。
        assertThat(counts.get("alpha")).isEqualTo(1L);
    }

    @Test
    void 未打标筛出的是真的没标签的() {
        List<SkillEntity> rows = skills.search(owner.id(), null, null, null, true, false,
            null, null, PageRequest.of(0, 100));
        assertThat(rows).extracting(SkillEntity::getId).contains(noTag).doesNotContain(alphaOnly);
    }

    @Test
    void 按标签筛出的是真的带这个标签的() {
        List<SkillEntity> rows = skills.search(owner.id(), null, null, "beta", false, false,
            null, null, PageRequest.of(0, 100));
        assertThat(rows).extracting(SkillEntity::getId).contains(alphaBeta).doesNotContain(alphaOnly);
    }

    private Map<String, Long> countByTag(List<SkillRepository.TagCount> rows) {
        return rows.stream().collect(java.util.stream.Collectors.toMap(
            SkillRepository.TagCount::getTag, SkillRepository.TagCount::getCount));
    }

    private CurrentUser newUser(String name) {
        UUID id = UUID.randomUUID();
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setOidcIssuer("https://probe.local");
        user.setOidcSubject("probe-" + id);
        user.setDisplayName(name);
        user.setStatus("active");
        user.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        user.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        users.save(user);
        return new CurrentUser(id, user.getOidcSubject(), name, null, "active");
    }

    private UUID newSkill(CurrentUser skillOwner, String slug, String visibility, String[] tags) {
        SkillEntity entity = new SkillEntity();
        entity.setId(UUID.randomUUID());
        entity.setSlug(slug + "-" + UUID.randomUUID());
        entity.setName(slug);
        entity.setDescription("测试用");
        entity.setOwnerUserId(skillOwner.id());
        entity.setVisibility(visibility);
        entity.setStatus(SkillStatus.ACTIVE);
        entity.setTags(tags);
        entity.setMetadataJson(Map.of());
        entity.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        entity.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return skills.save(entity).getId();
    }
}
