package com.skillmesh.api.skill;

import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.audit.AuditService;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.CursorCodec;
import com.skillmesh.api.common.CursorPage;
import com.skillmesh.api.org.OrgService;
import com.skillmesh.api.org.Roles;
import com.skillmesh.api.common.ErrorCodes;
import com.skillmesh.api.skill.dto.BatchArchiveRequest;
import com.skillmesh.api.skill.dto.BatchTagRequest;
import com.skillmesh.api.skill.dto.CreateSkillRequest;
import com.skillmesh.api.skill.dto.DeletedSkillResponse;
import com.skillmesh.api.skill.dto.SkillSourceInput;
import com.skillmesh.api.skill.dto.UpdateSkillRequest;
import com.skillmesh.api.skill.dto.SkillResponse;
import com.skillmesh.api.skill.dto.TagCountResponse;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SkillService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final SkillRepository skills;

    private final OrgService orgs;
    private final SourceRepository sources;

    private final AuditService audit;

    private final TransactionTemplate transactions;

    public SkillService(SkillRepository skills, OrgService orgs, SourceRepository sources,
                        AuditService audit, PlatformTransactionManager transactionManager) {
        this.skills = skills;
        this.orgs = orgs;
        this.sources = sources;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactionManager);
        // 每条一个独立事务。默认的 REQUIRED 会加入调用方的事务：
        // 一条失败就把整个外层标记成 rollback-only，已经改好的跟着被撤回来。
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 批量改标签。
     *
     * 每条在自己的事务里：批量的语义是「逐条独立」，一条没权限不该把已经改好的
     * 也一起撤回来。
     *
     * 权限不放宽：每条都照样走 update 里的判定（只有属主能改）。
     */
    public Map<String, Object> batchUpdateTags(CurrentUser user, BatchTagRequest request) {
        List<Map<String, Object>> succeeded = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        for (UUID id : request.ids()) {
            try {
                transactions.executeWithoutResult(status -> doUpdate(user, id,
                    new UpdateSkillRequest(null, null, request.tags(), null)));
                succeeded.add(Map.of("id", id.toString()));
            } catch (ApiException e) {
                failed.add(Map.of("id", id.toString(), "code", e.code(),
                    "message", String.valueOf(e.getMessage())));
            } catch (RuntimeException e) {
                failed.add(Map.of("id", id.toString(), "code", ErrorCodes.INTERNAL,
                    "message", String.valueOf(e.getMessage())));
            }
        }
        return batchResult(succeeded, failed);
    }

    /**
     * 批量归档（删除）。
     *
     * 逐条独立，权限与单条删除一致：个人 Skill 只有属主能删，
     * 团队/组织可见的要 publisher 及以上。
     */
    public Map<String, Object> batchArchive(CurrentUser user, BatchArchiveRequest request) {
        List<Map<String, Object>> succeeded = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        for (UUID id : request.ids()) {
            try {
                transactions.executeWithoutResult(status -> doArchive(user, id));
                succeeded.add(Map.of("id", id.toString()));
            } catch (ApiException e) {
                failed.add(Map.of("id", id.toString(), "code", e.code(),
                    "message", String.valueOf(e.getMessage())));
            } catch (RuntimeException e) {
                failed.add(Map.of("id", id.toString(), "code", ErrorCodes.INTERNAL,
                    "message", String.valueOf(e.getMessage())));
            }
        }
        return batchResult(succeeded, failed);
    }

    /** 批量结果：过了的和没过的分开报。 */
    private static Map<String, Object> batchResult(List<Map<String, Object>> succeeded,
                                                    List<Map<String, Object>> failed) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("succeeded", succeeded);
        result.put("failed", failed);
        return result;
    }

    @Transactional
    public SkillResponse create(CurrentUser user, CreateSkillRequest req) {
        requireVisibilitySupported(user, req);
        // 个人空间与组织空间是两套命名空间：只有建个人 Skill 时才查个人名下。
        // 原来不区分——用户自己有个个人 demo 时，在组织里建 demo 会被误挡，
        // 而唯一索引 uq_skills_org_slug 本来允许（它只约束组织内）。
        // 归档（删除）的 Skill 不占 slug：只查 active 的那些。
        if (slugTaken(req.organization_id(), user.id(), req.slug(), null)) {
            // 组织内的 slug 唯一（uq_skills_org_slug），团队之间也共用一个命名空间。
            // 预检拦住才回 409；撞唯一索引会变成 500，客户端看不出是重名。
            String where = req.organization_id() == null ? "" : "在该组织下";
            throw new ApiException(409, ErrorCodes.SLUG_TAKEN,
                "slug " + where + "已被占用: " + req.slug());
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        SkillEntity entity = new SkillEntity();
        entity.setId(UUID.randomUUID());
        entity.setSlug(req.slug());
        entity.setName(req.name());
        entity.setDescription(req.description());
        entity.setOwnerUserId(user.id());
        entity.setOrganizationId(req.organization_id());
        entity.setOwnerTeamId(req.owner_team_id());
        entity.setVisibility(req.visibility());
        entity.setStatus("active");
        entity.setTags(req.tags() == null ? new String[0] : req.tags().toArray(new String[0]));
        entity.setMetadataJson(req.metadata() == null ? Map.of() : req.metadata());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        SkillEntity saved = skills.save(entity);
        recordSource(user, saved, req.source());
        audit.record(user, "skill.created", "skill", saved.getId(), "success",
            Map.of("slug", saved.getSlug(), "visibility", saved.getVisibility()));
        return toResponse(saved);
    }

    /**
     * 记一条来源（设计文档 §7.1）。
     * 客户端没给就记 local_import——来源不明也要留痕，不能等于没有来源。
     */
    private void recordSource(CurrentUser user, SkillEntity skill, SkillSourceInput source) {
        SourceEntity row = new SourceEntity();
        row.setId(UUID.randomUUID());
        row.setSkillId(skill.getId());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setCreatedBy(user.id());

        if (source == null) {
            row.setSourceType("local_import");
            row.setProvider("local");
            row.setLicenseStatus("unknown");
            sources.save(row);
            return;
        }
        row.setSourceType(source.sourceType() == null ? "local_import" : source.sourceType());
        row.setProvider(source.provider() == null ? "local" : source.provider());
        row.setRepositoryUrl(source.repositoryUrl());
        row.setSubpath(source.subpath());
        row.setRefType(source.refType());
        row.setRefValue(source.refValue());
        row.setResolvedCommit(source.resolvedCommit());
        row.setLicenseExpression(source.licenseExpression());
        row.setLicenseStatus(source.licenseStatus() == null ? "unknown" : source.licenseStatus());
        sources.save(row);
    }

    @Transactional
    public SkillResponse update(CurrentUser user, UUID id, UpdateSkillRequest req) {
        return doUpdate(user, id, req);
    }

    /** 改展示性元数据。批量也走这里——批量用 TransactionTemplate 开着事务，不走代理。 */
    private SkillResponse doUpdate(CurrentUser user, UUID id, UpdateSkillRequest req) {
        SkillEntity entity = skills.findById(id).orElseThrow(() -> ApiException.notFound("Skill"));
        if (!user.id().equals(entity.getOwnerUserId())) {
            throw new ApiException(403, ErrorCodes.FORBIDDEN, "只有属主能改这个 Skill");
        }
        // 改了哪些字段要留下：光记「改过」看不出改了什么。
        List<String> changed = new ArrayList<>();
        if (req.name() != null) {
            entity.setName(req.name());
            changed.add("name");
        }
        if (req.description() != null) {
            entity.setDescription(req.description());
            changed.add("description");
        }
        if (req.tags() != null) {
            entity.setTags(req.tags().toArray(new String[0]));
            changed.add("tags");
        }
        if (req.metadata() != null) {
            entity.setMetadataJson(req.metadata());
            changed.add("metadata");
        }
        entity.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        SkillEntity saved = skills.save(entity);
        audit.record(user, "skill.updated", "skill", saved.getId(), "success",
            Map.of("fields", String.join(",", changed), "slug", saved.getSlug()));
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public SkillResponse get(CurrentUser user, UUID id) {
        return toResponse(requireVisibleSkill(user, id));
    }

    /**
     * 归档一个 Skill：软删除，数据全留着，回收期内能还原。
     *
     * 删除不是抹掉：版本、制品、安装记录都还在，status 改成 archived、
     * 记下归档时间就够了。客户端据此把本地那份一起删掉——
     * 本地只认服务端明确记下的删除，靠「查不到」推断会把换服务器导致的
     * 空库误当成删除，把本机内容全清掉（syncstate 里那个坑）。
     */
    @Transactional
    public void archive(CurrentUser user, UUID id) {
        doArchive(user, id);
    }

    /** 归档。批量也走这里——批量用 TransactionTemplate 开着事务，不走代理。 */
    private void doArchive(CurrentUser user, UUID id) {
        SkillEntity entity = skills.findById(id).orElseThrow(() -> ApiException.notFound("Skill"));
        requireArchiveRight(user, entity);
        if (!SkillStatus.ACTIVE.equals(entity.getStatus())) {
            // 已经删过了：不给第二次删除留入口，也别把归档当成功返回
            throw ApiException.notFound("Skill");
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        entity.setStatus(SkillStatus.ARCHIVED);
        entity.setArchivedAt(now);
        entity.setUpdatedAt(now);
        skills.save(entity);
        audit.record(user, "skill.archived", "skill", entity.getId(), "success",
            Map.of("slug", entity.getSlug()));
    }

    /**
     * 还原一个归档的 Skill。
     *
     * 归档期间可能有人用同一个 slug 建了新的：那时恢复会撞 slug 唯一性，
     * 直接报冲突，让人先把同名的处理掉。
     */
    @Transactional
    public SkillResponse restore(CurrentUser user, UUID id) {
        SkillEntity entity = skills.findById(id).orElseThrow(() -> ApiException.notFound("Skill"));
        requireArchiveRight(user, entity);
        if (SkillStatus.ACTIVE.equals(entity.getStatus())) {
            return toResponse(entity);
        }
        if (!SkillStatus.ARCHIVED.equals(entity.getStatus())) {
            throw ApiException.notFound("Skill");
        }
        if (slugTaken(entity.getOrganizationId(), entity.getOwnerUserId(),
            entity.getSlug(), entity.getId())) {
            throw new ApiException(409, ErrorCodes.SLUG_TAKEN,
                "slug 已被占用，先处理掉同名的 Skill: " + entity.getSlug());
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        entity.setStatus(SkillStatus.ACTIVE);
        entity.setArchivedAt(null);
        entity.setUpdatedAt(now);
        skills.save(entity);
        audit.record(user, "skill.restored", "skill", entity.getId(), "success",
            Map.of("slug", entity.getSlug()));
        return toResponse(entity);
    }

    /**
     * 列出自己删掉（归档）的 Skill。
     *
     * 客户端据此判断本地哪些要跟着删；人也要能看见自己删了什么、把它找回来。
     */
    @Transactional(readOnly = true)
    public CursorPage<DeletedSkillResponse> deleted(CurrentUser user, Integer limit, String cursor) {
        int size = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        CursorCodec.Cursor decoded = decodeCursor(cursor);
        List<SkillEntity> rows = skills.findArchived(user.id(), decoded.time(), decoded.id(),
            PageRequest.of(0, size + 1));
        return CursorPage.of(rows, size, SkillService::nextCursorOf)
            .map(page -> page.stream().map(entity -> new DeletedSkillResponse(entity.getId(),
                entity.getSlug(), entity.getName(), entity.getArchivedAt())).toList());
    }

    /**
     * 删除权：个人 Skill 只有属主能删，团队/组织的要 publisher 及以上。
     *
     * 与发布权同一档：能往里塞版本的人，才能把整个 Skill 收走。
     */
    private void requireArchiveRight(CurrentUser user, SkillEntity entity) {
        requireOwnerOrPublisher(user, entity, "只有属主能删除这个 Skill");
    }

    /**
     * 属主或 publisher（团队/组织可见的）才能做这件事。
     *
     * 删除与发布是同一档权限：能往里塞版本的人，才能把整个 Skill 收走。
     * 两件事的判定口径一样，所以只判一次——分开写迟早改一处漏一处。
     */
    private void requireOwnerOrPublisher(CurrentUser user, SkillEntity entity, String deniedMessage) {
        if ("team".equals(entity.getVisibility())
            || "organization".equals(entity.getVisibility())) {
            requireOrgPublisher(user, entity.getOrganizationId());
            return;
        }
        if (!user.id().equals(entity.getOwnerUserId())) {
            throw ApiException.forbidden(deniedMessage);
        }
    }

    /**
     * 同一个 slug 下是不是已经有别的 active Skill。
     *
     * 归档的 Skill 不再占 slug（唯一索引只约束 active），
     * 所以这里只查 active 的那些——包括用户归档之后新建的同名 Skill。
     *
     * excludeId 是要排除的那个：还原时排除自己，
     * 否则它自己就把自己挡住了。
     */
    private boolean slugTaken(UUID organizationId, UUID ownerUserId, String slug, UUID excludeId) {
        if (organizationId == null) {
            return skills.existsInPersonalSpace(ownerUserId, slug, SkillStatus.ACTIVE, excludeId);
        }
        return skills.existsInOrganization(organizationId, slug, SkillStatus.ACTIVE, excludeId);
    }

    /**
     * 取 Skill 并判可见性：看不见就当它不存在。
     *
     * 版本、审计、安装、同步、回收站这些入口都要过这一道。
     * 各写各的迟早写出不一样的口径——一处 404 一处 403 就泄露了它存不存在。
     */
    public SkillEntity requireVisibleSkill(CurrentUser user, UUID skillId) {
        SkillEntity entity = skills.findById(skillId)
            .orElseThrow(() -> ApiException.notFound("Skill"));
        if (!canView(user, entity)) {
            throw ApiException.notFound("Skill");
        }
        return entity;
    }

    @Transactional(readOnly = true)
    public CursorPage<SkillResponse> search(CurrentUser user, String q, String visibility, String tag,
                            boolean untagged, boolean mine, Integer limit, String cursor) {
        int size = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        CursorCodec.Cursor decoded = decodeCursor(cursor);
        // 团队/组织可见的 Skill：可见性条件交给 SQL 过滤，
        // 别把不该看到的查出来再在内存里筛——那等于没权限控制。
        List<SkillEntity> rows = skills.search(user.id(), normalize(q), visibility, tag,
            untagged, mine, decoded.time(), decoded.id(), PageRequest.of(0, size + 1));
        return CursorPage.of(rows, size, SkillService::nextCursorOf)
            .map(page -> page.stream().map(SkillService::toResponse).toList());
    }

    /**
     * 可见范围内的标签及数量。
     *
     * 口径与搜索一致：可见范围 + active。数量是给标签筛选器显示的——
     * 让用户知道点下去大概会看到多少条。
     */
    @Transactional(readOnly = true)
    public List<TagCountResponse> tags(CurrentUser user, boolean mine) {
        return skills.visibleTagCounts(user.id(), mine).stream()
            .map(row -> new TagCountResponse(row.getTag(), row.getCount()))
            .toList();
    }

    /**
     * 解码游标。没给游标就是第一页：时间与 id 都空着。
     *
     * 游标坏了是调用方的错，解码时统一报 400。
     */
    private static CursorCodec.Cursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return new CursorCodec.Cursor(null, null);
        }
        return CursorCodec.decode(cursor);
    }

    /** 下一页的起点：这一页最后一条的 (updated_at, id)。 */
    private static String nextCursorOf(SkillEntity entity) {
        return CursorCodec.encode(entity.getUpdatedAt(), entity.getId());
    }

    /**
     * 可见性判定（设计文档 §10 团队管理）。
     *
     * public 谁都能看；private 只有属主；organization 要组织成员；
     * team 要该团队成员，或该组织的 owner/admin。
     * 外部调用方（版本、制品、审计）都走这里，所以一处判就够。
     *
     * 读与审的口径刻意不同，别混：
     *   读/搜/装 —— 该团队成员；该组织的 owner/admin（{@link Roles#ORG_ADMIN_ROLES}）。
     *   审/批    —— 团队角色 reviewer+，没有团队成员行才回落组织角色（{@link OrgService#roleIn}）。
     *
     * 团队可见的内容归团队管：组织里的普通成员看不到别的团队的 Skill。
     * 但组织的 owner/admin 放行——他们建团队时没有团队成员行，
     * 却在审核队列里看得见并能批准它，「能批不能看」说不通。
     */
    public boolean canView(CurrentUser user, SkillEntity entity) {
        if ("public".equals(entity.getVisibility())
            || user.id().equals(entity.getOwnerUserId())) {
            return true;
        }
        if (entity.getOrganizationId() == null) {
            return false;
        }
        return switch (entity.getVisibility()) {
            case "team" -> (entity.getOwnerTeamId() != null
                    && orgs.isTeamMember(entity.getOwnerTeamId(), user.id()))
                || isOrgAdmin(entity.getOrganizationId(), user.id());
            case "organization" -> orgs.isOrgMember(entity.getOrganizationId(), user.id());
            default -> false;
        };
    }

    /**
     * 我是不是这个组织的管理员（owner/admin）？团队可见的 Skill 对组织管理员放行。
     *
     * 看的是组织角色本身，不看 {@code roleIn}——那个优先取团队角色，
     * 这里要的是「组织里我是不是管理员」这一条。
     * 角色可能为空（不是组织成员），判之前先挡一下：
     * {@code Set.of} 的 contains(null) 会抛 NPE。
     */
    private boolean isOrgAdmin(UUID organizationId, UUID userId) {
        String role = orgs.orgRoleOf(organizationId, userId);
        return role != null && Roles.ORG_ADMIN_ROLES.contains(role);
    }

    /**
     * 我与这个 Skill 有没有关系：属主、组织成员或团队成员。
     *
     * 与 {@link #canView} 不是一回事：那判的是「看不看得见」
     * （public 谁都看得见），这判的是「跟它有没有关系」——
     * 往别人的审计流水里写东西要的是这个，光看得见不够。
     *
     * 读与写的口径刻意不同，别混：
     *   看      —— public 谁都看得见。
     *   有关系  —— 属主、组织成员、团队成员。
     */
    public boolean isRelated(CurrentUser user, SkillEntity entity) {
        if (user.id().equals(entity.getOwnerUserId())) {
            return true;
        }
        if (entity.getOrganizationId() == null) {
            return false;
        }
        return orgs.isOrgMember(entity.getOrganizationId(), user.id())
            || (entity.getOwnerTeamId() != null
                && orgs.isTeamMember(entity.getOwnerTeamId(), user.id()));
    }

    /**
     * 创建时的可见性校验：team 必须挂到组织下的某个团队，
     * 组织内可见需要组织成员身份（不能凭空建一个没人看得到的团队 Skill）。
     *
     * 团队/组织可见还要 publisher 及以上——CLI 的检查只是建议，
     * 直接打 API 也得有服务端这道闸。
     */
    private void requireVisibilitySupported(CurrentUser user, CreateSkillRequest req) {
        String visibility = req.visibility();
        if (!Set.of("private", "public", "team", "organization").contains(visibility)) {
            throw new ApiException(400, ErrorCodes.VALIDATION_FAILED,
                "可见性只能是 private / public / team / organization");
        }
        if ("private".equals(visibility) || "public".equals(visibility)) {
            // private/public 不属于任何组织或团队：带着 organization_id 建出来的
            // Skill 会落进审核队列（队列按 organization_id is not null 过滤），
            // 私有内容就混进组织的审核页里了。
            if (req.organization_id() != null || req.owner_team_id() != null) {
                throw new ApiException(400, ErrorCodes.VALIDATION_FAILED,
                    "private/public 可见的 Skill 不属于任何组织或团队，别带 organization_id 或 owner_team_id");
            }
            return;
        }
        if (req.organization_id() == null) {
            throw new ApiException(400, ErrorCodes.VALIDATION_FAILED,
                visibility + " 可见必须指定 organization_id");
        }
        if (!orgs.isOrgMember(req.organization_id(), user.id())) {
            throw new ApiException(403, ErrorCodes.FORBIDDEN, "你不是该组织的成员");
        }
        if ("team".equals(visibility)) {
            if (req.owner_team_id() == null) {
                throw new ApiException(400, ErrorCodes.VALIDATION_FAILED,
                    "team 可见必须指定 owner_team_id");
            }
            // 团队得挂在同一个组织下，否则「组织角色」与「团队角色」是两回事，
            // 下面的 publisher 判断就无从谈起。
            if (!orgs.teamInOrg(req.organization_id(), req.owner_team_id())) {
                throw new ApiException(400, ErrorCodes.VALIDATION_FAILED,
                    "owner_team_id 必须属于 organization_id");
            }
        }
        // 团队与组织可见都要 publisher 及以上——发到组织是组织范围内的动作。
        requireOrgPublisher(user, req.organization_id());
    }

    /**
     * 发布权：组织角色 publisher 及以上。
     *
     * 判的是组织角色——与 CLI、手册一致。teamId 传 null 是刻意的：
     * roleIn 优先取团队角色，而接受邀请会写一行 team_role=member，
     * 把组织角色永久遮住，手册给的解法（提组织角色）在服务端就不生效了。
     */
    private void requireOrgPublisher(CurrentUser user, UUID organizationId) {
        String role = orgs.roleIn(organizationId, null, user.id());
        if (!OrgService.roleAtLeast(role, Roles.PUBLISHER_ROLE)) {
            throw new ApiException(403, ErrorCodes.FORBIDDEN,
                "在该组织里需要 publisher 及以上角色才能发布");
        }
    }

    /**
     * 给已有的 Skill 发新版本要有的发布权。
     *
     * 建 Skill 时按可见性判过一次发布权，但发新版本是另一次发布动作——
     * 只判可见性的话，团队的普通成员也能往团队的待审队列里塞一版内容。
     *
     * private 与 public：只有属主能发。private 的可见性本来就要求属主；
     * public 谁都看得见，若也放开发版，任何登录用户都能往别人的公开 Skill 上
     * 塞一版并顶掉 latest_version——而改名字（PATCH）与撤回都锁属主，
     * 只有发版不锁，契约自相矛盾。
     */
    public void requirePublishRight(CurrentUser user, SkillEntity skill) {
        requireOwnerOrPublisher(user, skill, "只有属主能发新版本");
    }

    private static String normalize(String q) {
        return q == null || q.isBlank() ? null : "%" + q.toLowerCase() + "%";
    }

    public static SkillResponse toResponse(SkillEntity entity) {
        return new SkillResponse(entity.getId(), entity.getSlug(), entity.getName(),
            entity.getDescription(), entity.getVisibility(), entity.getStatus(),
            entity.getOwnerUserId(), entity.getOrganizationId(), entity.getOwnerTeamId(),
            List.of(entity.getTags() == null ? new String[0] : entity.getTags()),
            entity.getMetadataJson(), entity.getLatestVersion(),
            entity.getCreatedAt(), entity.getUpdatedAt());
    }

}
