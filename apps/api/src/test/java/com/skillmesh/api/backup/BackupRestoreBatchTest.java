package com.skillmesh.api.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillmesh.api.backup.BackupService.TableBackup;
import io.minio.MinioClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 恢复是批量插的，不是逐行插的。
 *
 * 逐行插对着远程库就是每行一次往返：开发库七千多行，恢复要十几分钟，
 * 看着像挂住。批量插才是秒级。
 *
 * 这里不连库——拿个假的连接，数「批了几次」和「逐行插了几次」。
 */
class BackupRestoreBatchTest {

    @Test
    void 恢复按批插入不逐行插入() throws Exception {
        int rows = 1200;
        Path dir = Files.createTempDirectory("skillmesh-restore-batch-");
        Path file = dir.resolve("db/t.ndjson");
        Files.createDirectories(file.getParent());
        StringBuilder ndjson = new StringBuilder();
        for (int i = 0; i < rows; i++) {
            ndjson.append("{\"id\":\"").append(i).append("\",\"name\":\"n")
                .append(i).append("\"}\n");
        }
        Files.writeString(file, ndjson.toString(), StandardCharsets.UTF_8);

        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);

        BackupRestoreService service = new BackupRestoreService(
            mock(JdbcTemplate.class), mock(MinioClient.class), new ObjectMapper(),
            "bucket", mock(BackupService.class));

        int inserted = service.insertTable(connection, dir, "public", "t",
            new TableBackup("t", "db/t.ndjson", rows, ""), Map.of());

        assertThat(inserted).isEqualTo(rows);
        // 一批 500 行：1200 行 = 3 批。
        verify(statement, times(3)).executeBatch();
        verify(statement, times(rows)).addBatch();
        // 逐行插的路子不能有：那是十几分钟的来源。
        verify(statement, never()).executeUpdate();
    }
}
