package com.skillmesh.api.version;

import com.skillmesh.api.artifact.ArtifactEntity;
import com.skillmesh.api.artifact.ArtifactRepository;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.skill.SkillEntity;
import com.skillmesh.api.skill.SkillRepository;
import com.skillmesh.api.skill.SkillService;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 制品能不能被取走（设计文档 §10.1 撤回与隔离、§12 制品下载）。
 *
 * 制品本身没有状态——它只是一份内容。状态在**版本**上。
 * 而一份内容可以被多个版本引用（内容寻址，sha256 唯一，
 * 见 {@link ArtifactService#record}），所以「这个制品能不能下载」
 * 问的是「引用它的那些版本里，有没有一个此刻允许分发」。
 *
 * 允许分发的版本状态：
 *
 * - {@code published}：已发布，正常可下。
 * - {@code pending_review}：待审核。审核者要看得见内容才能审。
 * - {@code draft}：草稿。发布者自己的中间态。
 *
 * 不允许分发的：
 *
 * - {@code revoked}：撤回。设计文档 §10「撤回改变可用状态并保留记录」——
 *   内容留着是为了留痕与追责，不是为了继续给出去。
 * - {@code yanked}：下架。已经发出去的版本被撤下，不能再当新版本拉取。
 * - {@code rejected}：审核者驳回。没过审的内容不对外。
 * - {@code quarantined}：服务端扫描命中阻断规则（内容里有真凭据之类）。
 *   这正是最不能流出去的一种：它被隔离的原因就是内容有害。
 *
 * 属主仍然能取回自己撤回的版本吗？能——设计文档 §10.1 说「撤回不可逆。
 * 既有安装需要收到撤回提示并按组织策略处理」，撤回的是**对外分发**，
 * 不是删文件。属主自己要看自己撤回的内容是合理需求。
 * 但 quarantined 不给属主开后门：扫描结论说它有害，
 * 属主取不取是属主的事，但不该由服务端替他放开。
 */
@Service
public class ArtifactAccessPolicy {

    private static final String NOT_FOUND = "Artifact";

    private final SkillVersionRepository versions;
    private final SkillRepository skills;
    private final ArtifactRepository artifacts;
    private final SkillService skillService;

    public ArtifactAccessPolicy(SkillVersionRepository versions, SkillRepository skills,
                                ArtifactRepository artifacts, SkillService skillService) {
        this.versions = versions;
        this.skills = skills;
        this.artifacts = artifacts;
        this.skillService = skillService;
    }

    /**
     * 通过下载门禁的结果：制品本身，加上授权放行它的那一版。
     *
     * 留痕要记「这份内容属于哪个版本」，所以门禁把放行的那一版一起带出来。
     * 别让调用方回头自己查「第一个引用这个制品的版本」——一份内容可以被多个
     * 版本引用（内容寻址，sha256 唯一），第一个未必是放行的那一版，
     * 也未必属于调用者看得见的 Skill。
     */
    public record DownloadGrant(ArtifactEntity artifact, SkillVersionEntity version) {
    }

    /**
     * 这个制品能不能被这个人取走。不能就抛 404。
     *
     * 回 404 而不是 403：看不见的东西不泄露它存不存在，
     * 与 {@link SkillService#requireVisibleSkill} 同一口径。
     *
     * @return 通过门禁的制品与授权它的那一版。调用方直接拿它接着用，别再查一遍。
     */
    @Transactional(readOnly = true)
    public DownloadGrant requireDownloadable(CurrentUser user, UUID artifactId) {
        ArtifactEntity artifact = artifacts.findById(artifactId)
            .orElseThrow(() -> ApiException.notFound(NOT_FOUND));
        for (SkillVersionEntity version : versions.findByArtifactId(artifact.getId())) {
            SkillEntity skill = skills.findById(version.getSkillId()).orElse(null);
            if (skill == null || !skillService.canView(user, skill)) {
                continue;
            }
            if (distributable(user, version, skill)) {
                return new DownloadGrant(artifact, version);
            }
        }
        throw ApiException.notFound(NOT_FOUND);
    }

    /**
     * 这个版本的内容此刻能不能看（在线浏览文件走这里，与下载同一道门）。
     *
     * 属主对撤回、下架、驳回的版本仍可看——撤回的是对外分发，不是删文件。
     * quarantined 不给任何人开这个口子。
     */
    public void requireVersionContent(CurrentUser user, SkillVersionEntity version, SkillEntity skill) {
        if (!distributable(user, version, skill)) {
            throw ApiException.notFound("版本 " + version.getVersion());
        }
    }

    private boolean distributable(CurrentUser user, SkillVersionEntity version, SkillEntity skill) {
        return distributable(isOwner(user, skill), version.getStatus());
    }

    /**
     * 版本状态 → 此刻能不能分发。抽成静态纯函数是为了能直接测。
     *
     * @param isOwner 调用者是不是这个 Skill 的属主
     */
    static boolean distributable(boolean isOwner, VersionStatus status) {
        if (status == null) {
            return false;
        }
        return switch (status) {
            case published, pending_review, draft -> true;
            case revoked, yanked, rejected -> isOwner;
            case quarantined -> false;
        };
    }

    private boolean isOwner(CurrentUser user, SkillEntity skill) {
        return user != null && skill.getOwnerUserId() != null
            && skill.getOwnerUserId().equals(user.id());
    }
}
