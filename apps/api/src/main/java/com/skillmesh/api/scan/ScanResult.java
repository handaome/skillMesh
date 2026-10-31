package com.skillmesh.api.scan;

import java.util.List;

/**
 * 一次扫描的结论。
 *
 * @param status 扫描结论，对应 skill_scans.status
 * @param findings 命中项，file:line + 规则名 + 说明
 */
public record ScanResult(ScanStatus status, List<Finding> findings, String summary, long durationMs) {

    public record Finding(String path, int line, String rule, String detail) {}

    public boolean blocked() {
        return status == ScanStatus.failed || status == ScanStatus.error;
    }
}
