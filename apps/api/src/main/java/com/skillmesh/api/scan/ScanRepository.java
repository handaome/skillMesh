package com.skillmesh.api.scan;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScanRepository extends JpaRepository<ScanEntity, UUID> {

    /** 取这个版本最近一次扫描结论。 */
    Optional<ScanEntity> findFirstBySkillVersionIdOrderByStartedAtDesc(UUID skillVersionId);

    List<ScanEntity> findBySkillVersionIdOrderByStartedAtDesc(UUID skillVersionId);

    /** 一批版本的全部扫描，按开始时间倒序。 */
    List<ScanEntity> findBySkillVersionIdInOrderByStartedAtDesc(Collection<UUID> skillVersionIds);

    /**
     * 每个版本最近一次扫描，按版本 id 归拢。
     *
     * 列表页一次查齐：逐个版本 findFirst 一次，在远程库上就是 N 次往返
     * （本机到库的往返几十毫秒，N 大了页面直接卡住）。
     * 全局按 started_at 倒序，每个版本第一次出现的就是它最近的那次。
     */
    default Map<UUID, ScanEntity> findLatestByVersionIds(Collection<UUID> skillVersionIds) {
        if (skillVersionIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ScanEntity> latest = new HashMap<>();
        for (ScanEntity scan : findBySkillVersionIdInOrderByStartedAtDesc(skillVersionIds)) {
            latest.putIfAbsent(scan.getSkillVersionId(), scan);
        }
        return latest;
    }
}
