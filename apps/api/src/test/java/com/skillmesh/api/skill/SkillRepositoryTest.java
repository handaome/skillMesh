package com.skillmesh.api.skill;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmesh.api.auth.UserEntity;
import com.skillmesh.api.auth.UserRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * slug 占用的判定查询。
 *
 * 关键在 excludeId 为空时也要能查——那个分支只在
 * 「:excludeId is null」里出现，Hibernate 推不出参数类型就会报错。
 */
@SpringBootTest
@Transactional
class SkillRepositoryTest {

    /** 探针用的 slug。每次跑都不一样，不跟真实数据撞。 */
    private static final String PROBE_SLUG = "probe-slug-" + UUID.randomUUID();

    @Autowired
    private SkillRepository skills;

    @Autowired
    private UserRepository users;

    @Test
    void 个人空间查同名时排除项可以为空() {
        boolean taken = skills.existsInPersonalSpace(
            UUID.randomUUID(), PROBE_SLUG, SkillStatus.ACTIVE, null);
        assertThat(taken).isFalse();
    }

    @Test
    void 个人空间查同名时排除指定的那条() {
        boolean taken = skills.existsInPersonalSpace(
            UUID.randomUUID(), PROBE_SLUG, SkillStatus.ACTIVE, UUID.randomUUID());
        assertThat(taken).isFalse();
    }

    @Test
    void 组织空间查同名时排除项可以为空() {
        boolean taken = skills.existsInOrganization(
            UUID.randomUUID(), PROBE_SLUG, SkillStatus.ACTIVE, null);
        assertThat(taken).isFalse();
    }

    @Test
    void 组织空间查同名时排除指定的那条() {
        boolean taken = skills.existsInOrganization(
            UUID.randomUUID(), PROBE_SLUG, SkillStatus.ACTIVE, UUID.randomUUID());
        assertThat(taken).isFalse();
    }

    @Test
    void 个人空间里有同名就说占了() {
        UUID owner = newUser();
        SkillEntity entity = newSkill(owner, PROBE_SLUG, SkillStatus.ACTIVE);

        assertThat(skills.existsInPersonalSpace(owner, PROBE_SLUG, SkillStatus.ACTIVE, null)).isTrue();
        // 排除自己就不算占——还原时靠这个放行。
        assertThat(skills.existsInPersonalSpace(owner, PROBE_SLUG, SkillStatus.ACTIVE, entity.getId()))
            .isFalse();
        // 别人的同名不算：个人空间各管各的。
        assertThat(skills.existsInPersonalSpace(newUser(), PROBE_SLUG, SkillStatus.ACTIVE, null))
            .isFalse();
    }

    @Test
    void 归档的同名不算占用() {
        UUID owner = newUser();
        newSkill(owner, PROBE_SLUG, SkillStatus.ARCHIVED);

        // 归档的让出了 slug：可以重新建一个同名的。
        assertThat(skills.existsInPersonalSpace(owner, PROBE_SLUG, SkillStatus.ACTIVE, null)).isFalse();
    }

    /** 造一个用户：Skill 的属主有外键指向它。 */
    private UUID newUser() {
        UUID id = UUID.randomUUID();
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setOidcIssuer("https://probe.local");
        user.setOidcSubject("probe-" + id);
        user.setDisplayName("探针");
        user.setStatus("active");
        user.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        user.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        users.save(user);
        return id;
    }

    private SkillEntity newSkill(UUID owner, String slug, String status) {
        SkillEntity entity = new SkillEntity();
        entity.setId(UUID.randomUUID());
        entity.setSlug(slug);
        entity.setName("探针");
        entity.setDescription("测试用");
        entity.setOwnerUserId(owner);
        entity.setVisibility("private");
        entity.setStatus(status);
        entity.setTags(new String[0]);
        entity.setMetadataJson(Map.of());
        entity.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        entity.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return skills.save(entity);
    }
}
