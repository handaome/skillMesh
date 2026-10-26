package com.skillmesh.api.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillmesh.api.common.ContentDigest;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 备份与恢复验证（设计文档 §14：生产部署需要定期备份数据库与对象存储，并验证恢复流程）。
 *
 * 走 JDBC 和 MinIO SDK 而不是 pg_dump / mc：这两个 SDK 本来就在依赖里，
 * 备份脚本就不必在每台机器上再装一套客户端工具。
 *
 * export 抛 Exception 是因为 MinIO 的异常层级很杂（ErrorResponseException 等）；
 * 调用方本来就只有「备份成功 / 失败」两种处理方式。
 *
 * 导出按表流式写：ResultSet 边读边落 NDJSON，内存只占单行。
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    private final JdbcTemplate jdbc;
    private final MinioClient minio;
    private final ObjectMapper json;
    private final String bucket;

    public BackupService(JdbcTemplate jdbc, MinioClient minio,
                         ObjectMapper json,
                         @org.springframework.beans.factory.annotation.Value(
                             "${skillmesh.minio.bucket}") String bucket) {
        this.jdbc = jdbc;
        this.minio = minio;
        this.json = json;
        this.bucket = bucket;
    }

    /** 导出一份备份，返回清单。 */
    public Manifest export(Path target) throws Exception {
        Path dbDir = target.resolve("db");
        Path objectsDir = target.resolve("objects");
        Files.createDirectories(dbDir);
        Files.createDirectories(objectsDir);

        List<TableBackup> tables = new ArrayList<>();
        for (String table : listTables()) {
            Path file = dbDir.resolve(table + ".ndjson");
            long rows = dumpTable(table, file);
            tables.add(new TableBackup(table, "db/" + table + ".ndjson", rows, sha256(file)));
        }

        List<ObjectBackup> objects = new ArrayList<>();
        Iterable<Result<Item>> listed = minio.listObjects(ListObjectsArgs.builder()
            .bucket(bucket).recursive(true).build());
        for (Result<Item> result : listed) {
            Item item = result.get();
            Path file = objectsDir.resolve(item.objectName());
            Files.createDirectories(file.getParent());
            try (InputStream in = minio.getObject(GetObjectArgs.builder()
                    .bucket(bucket).object(item.objectName()).build());
                 OutputStream out = Files.newOutputStream(file)) {
                in.transferTo(out);
            }
            objects.add(new ObjectBackup(item.objectName(), "objects/" + item.objectName(),
                item.size(), sha256(file)));
        }

        Manifest manifest = new Manifest(
            OffsetDateTime.now(ZoneOffset.UTC).toString(), bucket, tables, objects);
        Files.write(target.resolve("manifest.json"),
            json.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
        log.info("备份完成：{} 张表，{} 个对象 -> {}", tables.size(), objects.size(), target);
        return manifest;
    }

    /**
     * 验证一份备份：逐个文件重算摘要与行数，对不上就报出来。
     * 这就是设计文档说的「验证恢复流程」——光备份不算数，能验才算。
     */
    public List<String> verify(Path target) throws IOException {
        Manifest manifest = json.readValue(target.resolve("manifest.json").toFile(),
            Manifest.class);
        List<String> problems = new ArrayList<>();

        for (TableBackup table : manifest.tables()) {
            Path file = target.resolve(table.file());
            if (!Files.exists(file)) {
                problems.add("缺文件：" + table.file());
                continue;
            }
            if (!sha256(file).equals(table.sha256())) {
                problems.add("摘要对不上：" + table.file());
            }
            long rows = countLines(file);
            if (rows != table.rows()) {
                problems.add("行数对不上：" + table.file() + "（清单 " + table.rows()
                    + "，实际 " + rows + "）");
            }
        }
        for (ObjectBackup object : manifest.objects()) {
            Path file = target.resolve(object.file());
            if (!Files.exists(file)) {
                problems.add("缺对象：" + object.key());
                continue;
            }
            if (Files.size(file) != object.bytes()) {
                problems.add("大小对不上：" + object.key());
            }
            if (!sha256(file).equals(object.sha256())) {
                problems.add("对象摘要对不上：" + object.key());
            }
        }
        return problems;
    }

    /**
     * 导出一份备份并当场校验。
     *
     * 两个入口——随应用启动跑的 {@code BackupRunner} 与独立跑的
     * {@code com.skillmesh.backup.BackupTool}——做的是同一件事：导出 → 校验 → 汇总。
     * 流程收在这里，两边只决定「校验没过怎么办」：备份脚本要退出码，
     * 随应用启动的那个只要记一条错误日志。
     */
    public BackupResult exportAndVerify(Path target) throws Exception {
        Manifest manifest = export(target);
        List<String> problems = verify(target);
        return new BackupResult(target, manifest, problems);
    }

    /**
     * 流式导出一张表：边读 ResultSet 边写 NDJSON。
     *
     * 一次 queryForList 会把整表读进内存——百万行的表就能把进程撑爆。
     * fetchSize 提示驱动分批取，内存占用只与单行大小有关。
     */
    private long dumpTable(String table, Path file) throws IOException {
        long rows = 0;
        try (var connection = jdbc.getDataSource().getConnection();
             var statement = connection.createStatement(
                 java.sql.ResultSet.TYPE_FORWARD_ONLY, java.sql.ResultSet.CONCUR_READ_ONLY)) {
            statement.setFetchSize(1000);
            try (var result = statement.executeQuery("select * from " + quote(table));
                 OutputStream out = Files.newOutputStream(file)) {
                int columns = result.getMetaData().getColumnCount();
                while (result.next()) {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    for (int i = 1; i <= columns; i++) {
                        row.put(result.getMetaData().getColumnLabel(i), result.getObject(i));
                    }
                    out.write(json.writeValueAsBytes(normalize(row)));
                    out.write('\n');
                    rows++;
                }
            }
        } catch (java.sql.SQLException e) {
            throw new IOException("导出表 " + table + " 失败：" + e.getMessage(), e);
        }
        return rows;
    }

    /**
     * JDBC 取回来的值不都能直接交给 Jackson：jsonb 回来的类型驱动私有、
     * byte[] 需要 base64。统一转成能序列化的形态，否则导出会中途炸掉。
     *
     * 不认识的类型一律 toString：PGobject、UUID 这类的字符串形式足够还原，
     * 类型信息在表结构里，不用在数据里重复一遍。
     */
    private Map<String, Object> normalize(Map<String, Object> row) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        row.forEach((column, value) -> out.put(column, normalizeValue(value)));
        return out;
    }

    private Object normalizeValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] bytes) {
            return java.util.Base64.getEncoder().encodeToString(bytes);
        }
        if (value instanceof java.sql.Array array) {
            // PostgreSQL 的数组列（text[] 之类）直接序列化会顺着驱动对象一路炸下去
            Object raw;
            try {
                raw = array.getArray();
            } catch (java.sql.SQLException e) {
                return String.valueOf(value);
            }
            if (raw instanceof Object[] items) {
                return java.util.Arrays.stream(items).map(this::normalizeValue).toList();
            }
            return raw == null ? null : String.valueOf(raw);
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant().toString();
        }
        if (value instanceof java.sql.Date date) {
            return date.toString();
        }
        if (value instanceof UUID || value instanceof Number || value instanceof Boolean
            || value instanceof String) {
            return value;
        }
        return String.valueOf(value);
    }

    private List<String> listTables() {
        return jdbc.queryForList(
            "select table_name from information_schema.tables "
                + "where table_schema = current_schema() and table_type = 'BASE TABLE' "
                + "order by table_name", String.class);
    }

    private long countLines(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            long lines = 0;
            int read;
            while ((read = in.read()) != -1) {
                if (read == '\n') {
                    lines++;
                }
            }
            return lines;
        }
    }

    private String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return ContentDigest.sha256(in);
        }
    }

    /** 表名来自 information_schema，但仍过一遍引号，避免关键字表名。 */
    private String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /**
     * 一次备份的结果：备份目录、清单、校验发现的问题。
     *
     * 汇总文案也在这里出：两个入口的汇总格式本来就一样，
     * 收在一处才不会写着写着走偏。
     */
    public record BackupResult(Path target, Manifest manifest, List<String> problems) {

        /** 校验是否通过。 */
        public boolean ok() {
            return problems.isEmpty();
        }

        /** 校验通过时的汇总文案。 */
        public String summary() {
            return "备份已校验：" + manifest.tables().size() + " 张表、"
                + manifest.objects().size() + " 个对象 -> " + target;
        }

        /** 校验没通过时的汇总文案。 */
        public String problemSummary() {
            return "备份校验发现 " + problems.size() + " 个问题：" + problems;
        }
    }

    /** 备份清单。 */
    public record Manifest(String createdAt, String bucket, List<TableBackup> tables,
                           List<ObjectBackup> objects) {}

    public record TableBackup(String table, String file, long rows, String sha256) {}

    public record ObjectBackup(String key, String file, long bytes, String sha256) {}
}
