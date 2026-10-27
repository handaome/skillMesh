package com.skillmesh.api.skill;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.auth.UserEntity;
import com.skillmesh.api.auth.UserRepository;
import com.skillmesh.api.skill.dto.BatchTagRequest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 批量改标签：逐条独立，权限不放宽。
 *
 * 关键在两处：
 * - 一条没权限不该把已经改好的也一起撤回来（挤在一个事务里就会）；
 * - 批量不���宽权限——只有属主能改，单条是这样，批量也得是这样。
 */
@SpringBootTest
class SkillBatchTagTest {

    @Autowired
    private SkillService skillService;

    @Autowired
    private SkillRepository skills;

    @Autowired
    private UserRepository users;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** 独立事务：批量走的是独立事务，看不见未提交的行。 */
    private TransactionTemplate committed;

    private CurrentUser owner;
    private CurrentUser other;
    private UUID ownSkillId;
    private UUID otherSkillId;
    private final List<UUID> userIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        committed = new TransactionTemplate(transactionManager);
        committed.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        committed.executeWithoutResult(status -> {
            owner = newUser("属主");
            other = newUser("别人");
            ownSkillId = newSkill(owner, "probe-own");
            otherSkillId = newSkill(other, "probe-other");
        });
    }

    @AfterEach
    void tearDown() {
        committed.executeWithoutResult(status -> {
            skills.deleteAll(skills.findAllById(List.of(ownSkillId, otherSkillId)));
            for (UUID id : userIds) {
                users.deleteById(id);
            }
        });
    }

    @Test
    void 一条改了另一条没权限两边都算数() {
        Map<String, Object> result = skillService.batchUpdateTags(owner,
            new BatchTagRequest(List.of(ownSkillId, otherSkillId), List.of("批量标签")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> succeeded = (List<Map<String, Object>>) result.get("succeeded");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> failed = (List<Map<String, Object>>) result.get("failed");

        assertThat(succeeded).as("失败明细：%s", failed).hasSize(1);
        assertThat(failed).hasSize(1);
        // 没权限的那条带原因：不是「失败了」，是「哪一条、为什么」。
        assertThat(failed.get(0)).containsKeys("id", "code", "message");
        assertThat(failed.get(0).get("code")).isEqualTo("FORBIDDEN");
        // 改成功的那条，标签真的改了。
        assertThat(skills.findById(ownSkillId).orElseThrow().getTags()).containsExactly("批量标签");
        // 没权限的那条，标签一个字没动。
        assertThat(skills.findById(otherSkillId).orElseThrow().getTags()).isEmpty();
    }

    @Test
    void 标签集合原样覆盖() {
        skillService.batchUpdateTags(owner, new BatchTagRequest(List.of(ownSkillId), List.of("甲", "乙")));
        Map<String, Object> result = skillService.batchUpdateTags(owner,
            new BatchTagRequest(List.of(ownSkillId), List.of("丙")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> succeeded = (List<Map<String, Object>>) result.get("succeeded");
        assertThat(succeeded).hasSize(1);
        // 覆盖式：不是往里加，是改成新的一组。
        assertThat(skills.findById(ownSkillId).orElseThrow().getTags()).containsExactly("丙");
    }

    private CurrentUser newUser(String name) {
        UUID id = UUID.randomUUID();
        userIds.add(id);
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setOidcIssuer("https://probe.local");
        user.setOidcSubject("probe-" + id);
        user.setDisplayName(name);
        user.setStatus("active");
        user.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        user.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        users.saveAndFlush(user);
        return new CurrentUser(id, user.getOidcSubject(), name, null, "active");
    }

    private UUID newSkill(CurrentUser skillOwner, String slug) {
        SkillEntity entity = new SkillEntity();
        entity.setId(UUID.randomUUID());
        entity.setSlug(slug + "-" + UUID.randomUUID());
        entity.setName(slug);
        entity.setDescription("测试用");
        entity.setOwnerUserId(skillOwner.id());
        entity.setVisibility("private");
        entity.setStatus("active");
        entity.setTags(new String[0]);
        entity.setMetadataJson(Map.of());
        entity.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        entity.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return skills.saveAndFlush(entity).getId();
    }
}
