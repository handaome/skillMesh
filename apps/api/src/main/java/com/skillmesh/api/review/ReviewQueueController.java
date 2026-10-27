package com.skillmesh.api.review;

import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.auth.CurrentUserService;
import com.skillmesh.api.common.CursorPage;
import com.skillmesh.api.review.dto.PendingReviewItem;
import com.skillmesh.api.review.dto.ReviewDetailResponse;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 审核队列（设计文档 §5.3）。 */
@RestController
@RequestMapping("/api/v1/reviews")
public class ReviewQueueController {

    private final ReviewQueueService queue;
    private final CurrentUserService currentUserService;

    public ReviewQueueController(ReviewQueueService queue, CurrentUserService currentUserService) {
        this.queue = queue;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    public CursorPage<PendingReviewItem> pending(@AuthenticationPrincipal Jwt jwt,
                              @RequestParam(required = false) String status,
                              @RequestParam(required = false) Integer limit,
                              @RequestParam(required = false) String cursor) {
        CurrentUser user = currentUserService.current(jwt);
        return queue.pending(user, status, limit, cursor);
    }

    @GetMapping("/{skillId}/{version}")
    public ReviewDetailResponse detail(@AuthenticationPrincipal Jwt jwt,
                                       @PathVariable UUID skillId,
                                       @PathVariable String version) {
        CurrentUser user = currentUserService.current(jwt);
        return queue.detail(user, skillId, version);
    }
}
