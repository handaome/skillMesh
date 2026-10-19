package com.skillmesh.api.review;

import com.skillmesh.api.auth.CurrentUserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 版本提交审核与审核结果（设计文档 §12、§10.1）。 */
@RestController
@RequestMapping("/api/v1/skills/{skillId}/versions/{version}")
public class ReviewController {

    private final ReviewService reviews;
    private final CurrentUserService currentUserService;

    public ReviewController(ReviewService reviews, CurrentUserService currentUserService) {
        this.reviews = reviews;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/submit")
    public Map<String, Object> submit(@AuthenticationPrincipal Jwt jwt,
                                      @PathVariable UUID skillId,
                                      @PathVariable String version,
                                      @Valid @RequestBody(required = false) Comment comment) {
        return reviews.submit(currentUserService.current(jwt), skillId, version, text(comment));
    }

    @PostMapping("/approve")
    public Map<String, Object> approve(@AuthenticationPrincipal Jwt jwt,
                                       @PathVariable UUID skillId,
                                       @PathVariable String version,
                                       @Valid @RequestBody(required = false) Comment comment) {
        return reviews.approve(currentUserService.current(jwt), skillId, version, text(comment));
    }

    @PostMapping("/reject")
    public Map<String, Object> reject(@AuthenticationPrincipal Jwt jwt,
                                      @PathVariable UUID skillId,
                                      @PathVariable String version,
                                      @Valid @RequestBody(required = false) Comment comment) {
        return reviews.reject(currentUserService.current(jwt), skillId, version, text(comment));
    }

    private String text(Comment comment) {
        return comment == null ? "" : comment.comment();
    }

    public record Comment(@Size(max = 2000) String comment) {}
}
