package com.skillmesh.api.scan;

import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 扫一遍制品并把结论落 skill_scans。
 *
 * 两条建版本的路径（整包上传、文件级同步）共用这一份：
 * 绕开哪一条都不该绕开服务端扫描这道闸。
 */
@Service
public class ArtifactScanService {

    private final ArtifactScanner scanner;
    private final ScanRepository scans;

    public ArtifactScanService(ArtifactScanner scanner, ScanRepository scans) {
        this.scanner = scanner;
        this.scans = scans;
    }

    /**
     * 扫一遍并落库。返回的结论里 blocked() 为真表示命中了阻断规则。
     *
     * 版本行要已经落库——skill_scans 有指向 skill_versions 的外键。
     */
    public ScanResult scanAndRecord(UUID skillVersionId, byte[] content) {
        ScanResult result = scanner.scan(new ByteArrayInputStream(content));
        OffsetDateTime completed = OffsetDateTime.now(ZoneOffset.UTC);
        ScanEntity row = new ScanEntity();
        row.setId(UUID.randomUUID());
        row.setSkillVersionId(skillVersionId);
        row.setScannerName(ArtifactScanner.NAME);
        row.setScannerVersion(ArtifactScanner.VERSION);
        row.setStatus(result.status());
        row.setFindingsJson(toJson(result.findings()));
        row.setSummary(result.summary());
        row.setStartedAt(completed.minusNanos(result.durationMs() * 1_000_000L));
        row.setCompletedAt(completed);
        scans.save(row);
        return result;
    }

    private List<Map<String, Object>> toJson(List<ScanResult.Finding> findings) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ScanResult.Finding finding : findings) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("path", finding.path());
            entry.put("line", finding.line());
            entry.put("rule", finding.rule());
            entry.put("detail", finding.detail());
            out.add(entry);
        }
        return out;
    }
}
