package com.skillmesh.api.org;

/**
 * 角色：组织与团队里的权限档位。
 *
 * 常量名就是数据库里存的值、也是接口里传的值：小写——
 * 枚举声明顺序也就是等级顺序（高到低）。
 *
 * 团队里没有 owner 那一档：owner 只能由现任 owner 直接任命，
 * 邀请授不了（见 {@link #orgOnly()}）。
 *
 * 词表只有这一份。等级顺序、可用范围、正则与 SQL 片段都从它派生
 * （见 {@link Roles}），别在别处再写一遍角色字面量。
 */
public enum Role {

    /** 组织 owner：团队里没有这一档。 */
    owner(true),

    /** 管理员：能管成员、邀请、建团队、管任意团队的成员。 */
    admin(false),

    /** 发布权档：能往组织/团队发布。 */
    publisher(false),

    /** 审核档：能审核放行。 */
    reviewer(false),

    /** 默认档位：邀请与「加成员」接口没给角色时用它。 */
    member(false),

    /** 只读档。 */
    viewer(false);

    /** 只属于组织域：团队成员行放不了这一档。 */
    private final boolean orgOnly;

    Role(boolean orgOnly) {
        this.orgOnly = orgOnly;
    }

    /** 这一档是不是只属于组织域（团队里没有 owner）。 */
    public boolean orgOnly() {
        return orgOnly;
    }
}
