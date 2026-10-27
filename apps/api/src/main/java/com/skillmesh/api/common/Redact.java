package com.skillmesh.api.common;

/*
 * @Class   com.skillmesh.api.common.Redact
 * @Project skillmesh
 * @Date    2026-10-27 10:40:00
 *
 * Copyright (c) 2026 安徽博微广成信息科技有限公司 All rights reserved.
 */

/**
 * 敏感内容的脱敏工具：写进审计流水前先过一道。
 *
 * 审计流水是按 Skill 可见的：谁看得见这个 Skill，就看得见它名下的每一条流水。
 * 流水里再塞本机绝对路径，等于把别人的目录布局（用户名、盘符、项目结构）摊给
 * 团队管理员。路径只留末两段——够看出「装到哪个技能目录」，又不还原得回去。
 */
public final class Redact {

    private Redact() {}

    /**
     * 把绝对路径压成可定位但不可还原的形式。
     *
     * 留末两段，前面用省略号：{@code C:\Users\张三\.claude\skills\demo} 变成
     * {@code …/skills/demo}。不到四段就原样返回——压完等于没压，
     * 反而让人看不懂。
     *
     * 空值原样返回：调用方自己判空，这里的口径不该比它更严。
     */
    public static String path(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        // Windows 的反斜杠也是分隔符，先统一成斜杠再数段数。
        String[] segments = value.replace('\\', '/').split("/", -1);
        if (segments.length < 4) {
            return value;
        }
        return "…/" + segments[segments.length - 2] + "/" + segments[segments.length - 1];
    }
}
