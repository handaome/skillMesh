package com.skillmesh.api.group;

/**
 * 技能集合的状态。
 *
 * 归档的集合让出 slug，可以重建一个同名的——
 * 与 Skill 同一口径（见详细设计的归档约定）。
 *
 * 常量名就是数据库里存的值、也是接口里传的值：小写。
 */
public enum GroupStatus {

    /** 正常。 */
    active,

    /** 归档。 */
    archived;

    /**
     * SQL 里的状态字面量。
     *
     * 原生 SQL 的片段必须是编译期常量（注解里的字符串拼接只认常量），
     * 所以这一份只能照着词表手写——改了上面的词表记得同步这里，
     * 下面的静态块盯着两边不漂。
     */
    public static final String ACTIVE_SQL = "'active'";

    static {
        if (!ACTIVE_SQL.equals(sql(active))) {
            throw new IllegalStateException("GroupStatus 的 SQL 字面量与词表对不上");
        }
    }

    private static String sql(GroupStatus status) {
        return "'" + status.name() + "'";
    }
}
