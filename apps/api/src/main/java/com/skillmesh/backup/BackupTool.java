package com.skillmesh.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillmesh.api.backup.BackupRestoreService;
import com.skillmesh.api.backup.BackupService;
import com.skillmesh.api.config.MinioClients;
import io.minio.MinioClient;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 独立跑一次备份或恢复，不启动应用。
 *
 * 用法：
 * <pre>
 * bash infra/scripts/backup.sh &lt;备份目录&gt;            # 导出
 * bash infra/scripts/backup.sh restore &lt;备份目录&gt;       # 恢复
 * </pre>
 *
 * 为什么要它：常规的备份入口是 BackupRunner——随应用启动跑。
 * 但应用启动要先过 Flyway 校验，迁移历史对不上时应用**根本起不来**，
 * 备份也就跟着跑不了（想备份的数据恰恰是最需要备份的时候）。
 * 这里绕开 Flyway，只要数据源与对象存储的连接。
 *
 * **它不能被主应用扫到**——主应用扫描 com.skillmesh.api，
 * 扫到这个类就会连带排除掉 FlywayAutoConfiguration（这里写了 exclude），
 * 主应用于是不再建表。所以它住在 com.skillmesh.backup（不在扫描范围内），
 * 而且 Config 是静态内部类，不参与组件扫描。
 */
public class BackupTool {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("用法：BackupTool <备份目录> | BackupTool restore <备份目录> [schema]");
            System.exit(2);
        }
        boolean restore = "restore".equals(args[0]);
        if (restore && args.length < 2) {
            System.err.println("用法：BackupTool restore <备份目录> [schema]");
            System.exit(2);
        }
        Path dir = Path.of(restore ? args[1] : args[0]);
        String schema = restore && args.length > 2 ? args[2] : "public";
        // Flyway 不参与：备份要能在一个迁移历史对不上的库上跑。
        System.setProperty("spring.flyway.enabled", "false");
        System.setProperty("spring.main.banner-mode", "off");
        try (ConfigurableApplicationContext ctx = SpringApplication.run(BackupTool.Config.class)) {
            if (restore) {
                BackupRestoreService restores = ctx.getBean(BackupRestoreService.class);
                BackupRestoreService.RestoreResult result = restores.restore(dir, schema);
                System.out.println(result.summary());
                return;
            }
            BackupService backup = ctx.getBean(BackupService.class);
            // 落在 <目录>/<时间戳>/ 里：一次导出一个目录，
            // 下一次不会把上一次的恢复点覆盖掉（备份目录里只留最新一份的，
            // 那就没有可回退的地方了）。
            Path run = backup.newRunDir(Path.of(args[0]));
            BackupService.BackupResult result = backup.exportAndVerify(run);
            if (!result.ok()) {
                System.err.println(result.problemSummary());
                System.exit(1);
            }
            System.out.println(result.summary());
        } catch (Exception e) {
            System.err.println((restore ? "恢复失败：" : "备份失败：") + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * 备份用的最小上下文：数据源 + MinIO + 备份服务。
     *
     * 它是静态内部类，不参与组件扫描——主应用扫到它就会连带把
     * 「排除 Flyway」带进主应用，主应用于是不建表。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {FlywayAutoConfiguration.class})
    @Import({BackupService.class, BackupRestoreService.class})
    static class Config {

        @Bean
        MinioClient minioClient(
            @Value("${skillmesh.minio.endpoint}") String endpoint,
            @Value("${skillmesh.minio.access-key}") String accessKey,
            @Value("${skillmesh.minio.secret-key}") String secretKey) {
            return MinioClients.create(endpoint, accessKey, secretKey);
        }

        @Bean
        BackupService backupService(JdbcTemplate jdbc, MinioClient minio, ObjectMapper json,
            @Value("${skillmesh.minio.bucket}") String bucket) {
            return new BackupService(jdbc, minio, json, bucket);
        }

        @Bean
        BackupRestoreService backupRestoreService(JdbcTemplate jdbc, MinioClient minio,
            ObjectMapper json, @Value("${skillmesh.minio.bucket}") String bucket,
            BackupService backups) {
            return new BackupRestoreService(jdbc, minio, json, bucket, backups);
        }
    }
}
