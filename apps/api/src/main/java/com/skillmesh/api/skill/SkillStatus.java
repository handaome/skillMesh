package com.skillmesh.api.skill;

/**
 * Skill 的状态。
 *
 * 在架与归档是日常的两档，加上被扫描拦下的 quarantined。
 * 归档不是删掉——数据留着，回收期内能还原。
 *
 * 常量名就是数据库里存的值、也是接口里传的值：小写。
 * 改名等于改数据，要改先写迁移。
 */
public enum SkillStatus {

    /** 在架。搜索与列表只返回这个状态的。 */
    active,

    /** 归档。删掉了但能还原。 */
    archived,

    /** 被扫描拦下。 */
    quarantined;

    /**
     * SQL 里的状态字面量。
     *
     * 原生 SQL 的片段必须是编译期常量（注解里的字符串拼接只认常量），
     * 所以这两份只能照着词表手写——改了上面的词表记得同步这里，
     * 下面的静态块盯着两边不漂。
     */
    public static final String ACTIVE_SQL = "'active'";
    public static final String ARCHIVED_SQL = "'archived'";

    static {
        if (!ACTIVE_SQL.equals(sql(active)) || !ARCHIVED_SQL.equals(sql(archived))) {
            throw new IllegalStateException("SkillStatus 的 SQL 字面量与词表对不上");
        }
    }

    private static String sql(SkillStatus status) {
        return "'" + status.name() + "'";
    }
}
