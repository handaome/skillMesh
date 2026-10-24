package com.skillmesh.api.artifact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 启动时清一次桶里的孤儿对象。
 *
 * 用法：--skillmesh.maintenance.purge-orphans=true
 * 只演练不删：加 --skillmesh.maintenance.purge-orphans=delete 才真删。
 *
 * 删桶里的东西不可逆，所以默认只报清单，让人看过再决定。
 */
@Component
@ConditionalOnProperty("skillmesh.maintenance.purge-orphans")
public class ArtifactStorageMaintenanceRunner implements ApplicationRunner {

    private static final Logger log =
        LoggerFactory.getLogger(ArtifactStorageMaintenanceRunner.class);

    private final ArtifactStorageMaintenanceService maintenance;
    private final String mode;

    public ArtifactStorageMaintenanceRunner(ArtifactStorageMaintenanceService maintenance,
            @org.springframework.beans.factory.annotation.Value(
                "${skillmesh.maintenance.purge-orphans}") String mode) {
        this.maintenance = maintenance;
        this.mode = mode;
    }

    @Override
    public void run(ApplicationArguments args) {
        int deleted = maintenance.purgeOrphans(!"delete".equals(mode));
        if (deleted > 0) {
            log.info("清掉 {} 个孤儿对象", deleted);
        }
    }
}
