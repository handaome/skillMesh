package com.skillmesh.api.skill;

/**
 * Skill 的状态。
 *
 * 只有两个：在架的与归档的。归档不是删掉——数据留着，回收期内能还原。
 *
 * 状态散着写迟早写错（大小写、拼写），而它进了 SQL 的唯一索引约束，
 * 写错一个字母就是「明明没重名却报重名」。
 */
public final class SkillStatus {

    /** 在架。搜索与列表只返回这个状态的。 */
    public static final String ACTIVE = "active";

    /** 归档。删掉了但能还原。 */
    public static final String ARCHIVED = "archived";

    /**
     * SQL 里的状态字面量。
     *
     * 原生 SQL 的片段必须是编译期常量（注解里的字符串拼接只认常量），
     * 所以这两份只能照着词表手写——改了上面那两份记得同步这里。
     */
    public static final String ACTIVE_SQL = "'active'";
    public static final String ARCHIVED_SQL = "'archived'";

    private SkillStatus() {}
}
