package com.skillmesh.api.auth;

import com.skillmesh.api.auth.dto.UserAccount;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.ErrorCodes;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 站点管理员才能做的事：建号、看账号列表。
 *
 * 自助注册关掉（SM_ALLOW_REGISTER=false）后，账号只能从这里来——
 * 服务端只剩一个管理员时，别人怎么进来全靠这一条路。
 */
@Service
public class UserAdminService {

    private final UserRepository users;
    private final LocalAuthService auth;

    public UserAdminService(UserRepository users, LocalAuthService auth) {
        this.users = users;
        this.auth = auth;
    }

    /** 建号：用户名唯一，密码哈希后存库。新账号是普通用户。 */
    @Transactional
    public UserAccount create(CurrentUser actor, String username, String password,
                               String displayName, String email) {
        requireSiteAdmin(actor);
        return UserAccount.of(auth.createAccount(username, password, displayName, email, "user"));
    }

    /** 账号列表：管理员看得到所有人的账号。 */
    @Transactional(readOnly = true)
    public List<UserAccount> list(CurrentUser actor) {
        requireSiteAdmin(actor);
        return users.findAllByOrderByCreatedAtAsc().stream().map(UserAccount::of).toList();
    }

    /** 禁用或启用账号。禁用后登不进来，账号与数据都留着。 */
    @Transactional
    public UserAccount setStatus(CurrentUser actor, UUID userId, String status) {
        requireSiteAdmin(actor);
        if (!"active".equals(status) && !"disabled".equals(status)) {
            throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "状态只能是 active 或 disabled");
        }
        // 不能改自己的状态：站点只剩一个管理员时，这一手等于把系统锁死。
        if (actor.id().equals(userId)) {
            throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "不能改自己的账号状态");
        }
        UserEntity user = users.findById(userId)
            .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "账号不存在"));
        user.setStatus(status);
        return UserAccount.of(users.save(user));
    }

    /** 站内管理员才能过这一关。 */
    private static void requireSiteAdmin(CurrentUser actor) {
        if (!actor.siteAdmin()) {
            throw new ApiException(403, ErrorCodes.FORBIDDEN, "只有站点管理员能做这件事");
        }
    }
}
