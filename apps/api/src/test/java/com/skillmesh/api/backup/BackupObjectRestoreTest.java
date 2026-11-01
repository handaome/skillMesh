package com.skillmesh.api.backup;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmesh.api.backup.BackupService.Manifest;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 备份的对象存储那一半（设计文档 §14「生产部署需要定期备份数据库与对象存储」）。
 *
 * 数据那半边在 {@link BackupRestoreTest} 里。这一半要真碰桶：导出的备份里
 * 得有那个对象，删掉桶里的那份之后还能恢复回去。
 *
 * 探针对象用唯一的名字，跑完就删——桶是共享的，不能留东西。
 */
@SpringBootTest
class BackupObjectRestoreTest {

    private static final String CONTENT = "探针对象内容\n";

    @Autowired
    private BackupService backups;

    @Autowired
    private BackupRestoreService restores;

    @Autowired
    private MinioClient minio;

    @Value("${skillmesh.minio.bucket}")
    private String bucket;

    @Test
    void 桶里的对象能导出再恢复回去() throws Exception {
        String key = "probe/backup-object-" + UUID.randomUUID() + ".txt";
        byte[] content = CONTENT.getBytes(StandardCharsets.UTF_8);
        minio.putObject(PutObjectArgs.builder().bucket(bucket).object(key)
            .stream(new ByteArrayInputStream(content), content.length, -1)
            .contentType("text/plain").build());
        Path dir = Files.createTempDirectory("skillmesh-backup-objects-");
        try {
            Manifest manifest = backups.export(dir);

            // 导出的备份里有这个对象，清单里也记着。
            Path file = dir.resolve("objects/" + key);
            assertThat(Files.exists(file)).as("备份里要有 %s", key).isTrue();
            assertThat(Files.readString(file)).isEqualTo(CONTENT);
            assertThat(manifest.objects()).extracting(BackupService.ObjectBackup::key)
                .contains(key);

            // 删掉桶里的那份，再恢复回去。
            minio.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
            assertThat(restores.restoreObjects(dir)).isPositive();

            try (InputStream in = minio.getObject(
                    GetObjectArgs.builder().bucket(bucket).object(key).build())) {
                assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo(CONTENT);
            }
        } finally {
            minio.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
            deleteTree(dir);
        }
    }

    private void deleteTree(Path dir) throws Exception {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // 临时目录：删不掉留给系统清理。
                }
            });
        }
    }
}
