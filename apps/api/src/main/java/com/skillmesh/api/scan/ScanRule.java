package com.skillmesh.api.scan;

import java.util.regex.Pattern;

/**
 * 一条扫描规则。规则清单与 CLI 端 skillscan/secrets.go 保持同名同序，
 * 两端各自实现、版本号不同，便于对照（详细设计 §10 内容扫描）。
 */
public record ScanRule(String name, Pattern pattern, Severity severity) {

    public enum Severity {
        /** 只是提醒，不影响发布。 */
        WARNING,
        /** 命中就不能发布。 */
        BLOCKING
    }

    public boolean blocking() {
        return severity == Severity.BLOCKING;
    }
}
