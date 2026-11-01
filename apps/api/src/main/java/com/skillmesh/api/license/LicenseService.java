package com.skillmesh.api.license;

import com.skillmesh.api.skill.LicenseStatus;
import com.skillmesh.api.skill.SourceEntity;
import com.skillmesh.api.skill.SourceRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 许可证的自动识别与回填。
 *
 * 客户端知道得比服务端多（它认得本机的许可证文件），但客户端也可能没给——
 * 老客户端、脚本发布、从 Git 导入都会缺这一项。服务端手里有制品，
 * 自己也能认一份：认出来就补上，省得来源行一直挂着 unknown。
 *
 * 补的只是「认出是哪一份」（detected）。能不能再分发仍然要人判断。
 */
@Service
public class LicenseService {

    private static final Logger log = LoggerFactory.getLogger(LicenseService.class);

    private final SourceRepository sources;

    public LicenseService(SourceRepository sources) {
        this.sources = sources;
    }

    /**
     * 制品里认出许可证就填到来源行上。
     *
     * 客户端给过表达式就不动：那是发布方的说法，服务端只在自己没得说时补一手。
     *
     * @return 认出来的标识；没认出来或用不上就返回 null
     */
    @Transactional
    public String detectFromArtifact(UUID skillId, byte[] content) {
        String detected = LicenseDetector.detectInZip(content);
        if (detected == null) {
            return null;
        }
        List<SourceEntity> rows = sources.findBySkillIdOrderByCreatedAtDesc(skillId);
        for (SourceEntity row : rows) {
            String existing = row.getLicenseExpression();
            if (existing != null && !existing.isBlank()) {
                return null;
            }
            row.setLicenseExpression(detected);
            row.setLicenseStatus(LicenseStatus.detected);
            sources.save(row);
            log.info("Skill {} 的许可证识别为 {}（客户端没给，服务端从制品里认的）",
                skillId, detected);
            return detected;
        }
        return null;
    }
}
