package com.skillmesh.api.group;

import com.skillmesh.api.auth.CurrentUserService;
import com.skillmesh.api.group.dto.GroupRequest;
import com.skillmesh.api.group.dto.GroupResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 技能集合（设计文档 §6 的一组 Skill 一起装）。 */
@RestController
@RequestMapping("/api/v1/groups")
public class GroupController {

    private final GroupService groups;
    private final CurrentUserService currentUserService;

    public GroupController(GroupService groups, CurrentUserService currentUserService) {
        this.groups = groups;
        this.currentUserService = currentUserService;
    }

    /** 我可见的集合。 */
    @GetMapping
    public List<GroupResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return groups.list(currentUserService.current(jwt));
    }

    @PostMapping
    public ResponseEntity<GroupResponse> create(@AuthenticationPrincipal Jwt jwt,
                                                @Valid @RequestBody GroupRequest req) {
        GroupResponse created = groups.create(currentUserService.current(jwt), req);
        return ResponseEntity.created(URI.create("/api/v1/groups/" + created.id())).body(created);
    }

    @GetMapping("/{groupId}")
    public GroupResponse get(@AuthenticationPrincipal Jwt jwt,
                             @PathVariable UUID groupId) {
        return groups.get(currentUserService.current(jwt), groupId);
    }

    /**
     * 按 slug 取集合。
     *
     * CLI 与脚本按 slug 引用集合，跟 Skill 用 slug 引用一个道理——
     * 让用户去记 UUID 没有意义。
     */
    @GetMapping("/slug/{slug}")
    public GroupResponse getBySlug(@AuthenticationPrincipal Jwt jwt,
                                   @PathVariable String slug) {
        return groups.getBySlug(currentUserService.current(jwt), slug);
    }

    /** 改名称、描述与成员。slug 与可见性改不了——改了会让别人手里的引用断掉。 */
    @PatchMapping("/{groupId}")
    public GroupResponse update(@AuthenticationPrincipal Jwt jwt,
                                @PathVariable UUID groupId,
                                @Valid @RequestBody GroupRequest req) {
        return groups.update(currentUserService.current(jwt), groupId, req);
    }

    /** 归档集合。软删除：slug 让出来，可以重建一个同名的。 */
    @DeleteMapping("/{groupId}")
    public ResponseEntity<Void> archive(@AuthenticationPrincipal Jwt jwt,
                                        @PathVariable UUID groupId) {
        groups.archive(currentUserService.current(jwt), groupId);
        return ResponseEntity.noContent().build();
    }
}
