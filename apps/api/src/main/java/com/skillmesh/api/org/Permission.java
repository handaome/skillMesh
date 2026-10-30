package com.skillmesh.api.org;

/**
 * 权限点：能做什么。全站只有这一份。
 *
 * 角色是「你是谁」，权限是「你能做什么」——两者不是一回事：
 * 组织里的 reviewer 能审核但不能发布，团队里的 admin 能管成员但管不了另一个团队。
 * 所以门槛一律用这里的权限点表达，不再在各处手写角色名。
 *
 * 三类权限，判定时取的角色来源不同（见 {@link AuthorizationService}）：
 *   组织域 —— 只看组织角色（ORG_ 前缀）；
 *   团队域 —— 团队角色优先，没有团队成员行才回落组织角色；
 *   业务域 —— 随作用域取角色（SKILL_/GROUP_ 前缀）。
 */
public enum Permission {

    /** 查看组织成员花名册。 */
    ORG_VIEW_MEMBERS("查看组织成员花名册"),

    /** 邀请成员加入组织。 */
    ORG_INVITE("邀请成员加入组织"),

    /** 改成员角色、启用禁用成员。 */
    ORG_MANAGE_MEMBERS("管理组织成员"),

    /** 建团队、改团队。 */
    ORG_MANAGE_TEAMS("管理团队"),

    /** 授予 owner 角色——只有 owner 能做，给了 admin 就是提权。 */
    ORG_GRANT_OWNER("授予 owner 角色"),

    /** 改变 owner 身份（授予或剥夺）。 */
    ORG_REVOKE_OWNER("改变 owner 身份"),

    /** 查看团队成员。 */
    TEAM_VIEW_MEMBERS("查看团队成员"),

    /** 改团队成员角色、往团队里加成员。 */
    TEAM_MANAGE_MEMBERS("管理团队成员"),

    /** 发布 Skill 到组织或团队。 */
    SKILL_PUBLISH("发布 Skill"),

    /** 审核放行待审版本。 */
    SKILL_REVIEW("审核放行版本"),

    /** 把集合（Group）挂到组织或团队上。 */
    GROUP_PUBLISH("发布集合");

    private final String label;

    Permission(String label) {
        this.label = label;
    }

    /** 中文说明，用于拒绝时的提示。 */
    public String label() {
        return label;
    }
}
