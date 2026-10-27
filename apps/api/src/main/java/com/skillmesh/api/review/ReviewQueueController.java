package com.skillmesh.api.review;

import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.auth.CurrentUserService;
import com.skillmesh.api.common.CursorPage;
import com.skillmesh.api.review.dto.BatchDecideRequest;
import com.skillmesh.api.review.dto.PendingReviewItem;
import com.skillmesh.api.review.dto.ReviewDetailResponse;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 审核队列（设计文档 §5.3）。 */
@RestController
@RequestMapping("/api/v1/reviews")
public class ReviewQueueController {

    private final ReviewQueueService queue;
    private final ReviewService reviews;
    private final CurrentUserService currentUserService;

    public ReviewQueueController(ReviewQueueService queue, ReviewService reviews,
                                 CurrentUserService currentUserService) {
        this.queue = queue;
        this.reviews = reviews;
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

    /**
     * 批量审核。
     *
     * 逐条独立：一条过了另一条没过，两边都报清楚。共用一个事务的话，
     * 第一条失败会把已经放行的也一起撤回来。
     *
     * 权限不放宽——每条照样走单条审核的那几道闸（reviewer 角色、
     * 不能审自己发布的版本、扫描结论阻断）。批量只是省了逐条点开详情页。
     */
    @PostMapping("/batch-decide")
    public Map<String, Object> batchDecide(@AuthenticationPrincipal Jwt jwt,
                                           @Valid @RequestBody BatchDecideRequest req) {
        return reviews.batchDecide(currentUserService.current(jwt), req);
    }
}
