package com.skillmesh.api.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillmesh.api.backup.BackupService.Manifest;
import com.skillmesh.api.backup.BackupService.ObjectBackup;
import com.skillmesh.api.backup.BackupService.TableBackup;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.BufferedReader;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 把一份备份恢复回去（设计文档 §14：生产部署需要定期备份数据库与对象存储，
 * 并验证恢复流程）。
 *
 * 备份器只做「导出 + 校验」——校验的是备份自己没坏。
 * 那不等于能恢复：行插不回去、顺序不对、数组列插不进去，
 * 都要真恢复一次才知道。这里就是那个「真恢复一次」。
 *
 * 恢复的三个要点：
 * - **顺序**：有外键的表得先插父表。唯一被延后检查的约束
 *   （skills.latest_version → skill_versions）不参与排序，它在提交时才检查。
 * - **类型**：导出的值都成了 JSON 里的字符串，插回去要按列的类型转
 *   （uuid、timestamptz、jsonb、text[] 各有各的写法），靠 SQL 里的显式转型
 *   （{@code ?::uuid}）而不是让驱动猜。
 * - **空库**：目标表里还有数据就拒绝恢复——恢复是往空库里灌，
 *   往有数据的库里灌要么覆盖要么报冲突，两样都不该悄悄发生。
 */
@Service
public class BackupRestoreService {

    private static final Logger log = LoggerFactory.getLogger(BackupRestoreService.class);

    private final JdbcTemplate jdbc;
    private final MinioClient minio;
    private final ObjectMapper json;
    private final String bucket;
    private final BackupService backups;

    public BackupRestoreService(JdbcTemplate jdbc, MinioClient minio, ObjectMapper json,
                                @org.springframework.beans.factory.annotation.Value(
                                    "${skillmesh.minio.bucket}") String bucket,
                                BackupService backups) {
        this.jdbc = jdbc;
        this.minio = minio;
        this.json = json;
        this.bucket = bucket;
        this.backups = backups;
    }

    /**
     * 恢复一份备份：数据库表 + 对象存储。
     *
     * @param schema 恢复到哪个 schema，默认当前 schema（public）。
     */
    public RestoreResult restore(Path target, String schema) throws Exception {
        RestoreResult restored = restoreDatabase(target, schema);
        int objects = restoreObjects(target);
        RestoreResult result = new RestoreResult(target, schema, restored.rows(), objects);
        log.info("恢复完成：schema {}，{} 行、{} 个对象", schema, result.rows(), result.objects());
        return result;
    }

    /**
     * 只恢复数据库部分。
     *
     * 对象存储那部分是「把备份里的对象放回桶里」——内容寻址，同一份内容
     * 覆盖回去还是那一份。要单独恢复表（比如只查数据不碰桶）时走这里。
     */
    public RestoreResult restoreDatabase(Path target, String schema) throws Exception {
        List<String> problems = backups.verify(target);
        if (!problems.isEmpty()) {
            // 校验没过还往下走，恢复出来的就是一份坏数据——宁可不恢复。
            throw new IllegalStateException("备份校验没过，不恢复：" + problems);
        }
        Manifest manifest = json.readValue(target.resolve("manifest.json").toFile(),
            Manifest.class);
        List<String> notEmpty = nonEmptyTables(schema, manifest);
        if (!notEmpty.isEmpty()) {
            throw new IllegalStateException("目标库里这些表还有数据，恢复会冲突："
                + notEmpty + "（恢复是往空库里灌；先把目标库清空或换一台机器）");
        }
        int rows = restoreTables(target, manifest, schema);
        log.info("恢复完成：schema {}，{} 行", schema, rows);
        return new RestoreResult(target, schema, rows, 0);
    }

    /** 数据库部分恢复多少行。 */
    private int restoreTables(Path target, Manifest manifest, String schema) throws Exception {
        List<String> order = restoreOrder(schema, manifest);
        Map<String, Map<String, String>> castTypes = new HashMap<>();
        int rows = 0;
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                // 可延后检查的约束（skills.latest_version → skill_versions）
                // 留到提交时再查：插 skills 时 skill_versions 还没插。
                statement.execute("set constraints all deferred");
            }
            try {
                for (String table : order) {
                    TableBackup backup = tableBackup(manifest, table);
                    if (backup == null) {
                        continue;
                    }
                    if (!tableExists(schema, table)) {
                        log.warn("备份里有表 {}，目标 schema 里没有，跳过", table);
                        continue;
                    }
                    castTypes.computeIfAbsent(table, key -> castTypesOf(schema, key));
                    rows += insertTable(connection, target, schema, table, backup,
                        castTypes.get(table));
                }
                resetSequences(connection, schema, manifest);
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        }
        return rows;
    }

    /** 插一张表：逐行读 NDJSON，按列的类型转型后插回去。 */
    private int insertTable(Connection connection, Path target, String schema, String table,
                            TableBackup backup, Map<String, String> castTypes) throws Exception {

        Path file = target.resolve(backup.file());
        List<String> columns = new ArrayList<>();
        String sql = null;
        int rows = 0;
        // 逐行读，不整个读进堆：一张大表的 NDJSON 能有几百兆。
        try (BufferedReader reader = Files.newBufferedReader(file,
                java.nio.charset.StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> row = json.readValue(line, LinkedHashMap.class);
                List<String> rowColumns = new ArrayList<>(row.keySet());
                if (sql == null) {
                    columns = rowColumns;
                    sql = insertSql(schema, table, columns, castTypes);
                } else if (!columns.equals(rowColumns)) {
                    // 每行的列不一样：按位置填参数就会把值填到别的列上。
                    throw new IllegalStateException("备份文件 " + backup.file()
                        + " 里各行的列不一致，拒绝恢复");
                }
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    for (int i = 0; i < columns.size(); i++) {
                        String column = columns.get(i);
                        String value = restoreValue(row.get(column), castTypes.get(column));
                        if (value == null) {
                            statement.setNull(i + 1, java.sql.Types.VARCHAR);
                        } else {
                            statement.setString(i + 1, value);
                        }
                    }
                    statement.executeUpdate();
                }
                rows++;
            }
        }
        return rows;
    }

    /** 拼 INSERT：每个占位符带上目标列的类型，驱动不用猜。 */
    private String insertSql(String schema, String table, List<String> columns,
                             Map<String, String> castTypes) {
        StringBuilder sql = new StringBuilder("insert into ")
            .append(quote(schema)).append('.').append(quote(table)).append(" (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(quote(columns.get(i)));
        }
        sql.append(") values (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            String cast = castTypes.get(columns.get(i));
            sql.append(cast == null ? "?" : "?::" + cast);
        }
        return sql.append(')').toString();
    }

    /**
     * 把导出的值转成能插回这一列的字符串。
     *
     * 数组列（text[]）要拼成 PG 的数组字面量；jsonb 列要的是 JSON 文本；
     * 其余的（uuid、timestamptz、数字……）转成字符串后由 SQL 里的显式转型接手。
     */
    private String restoreValue(Object value, String castType) {
        if (value == null) {
            return null;
        }
        if (castType != null && castType.endsWith("[]")) {
            return pgArray(value);
        }
        if ("jsonb".equals(castType) || "json".equals(castType)) {
            if (value instanceof String text) {
                return text;
            }
            try {
                return json.writeValueAsString(value);
            } catch (Exception e) {
                return String.valueOf(value);
            }
        }
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Boolean flag) {
            return flag ? "true" : "false";
        }
        if (value instanceof Number number) {
            return number.toString();
        }
        return String.valueOf(value);
    }

    /** PG 的数组字面量：{a,b,"c,d"}。带逗号、空格、引号的元素要加引号转义。 */
    private String pgArray(Object value) {
        List<?> items = value instanceof List<?> list ? list : List.of(String.valueOf(value));
        StringBuilder out = new StringBuilder("{");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(pgArrayElement(items.get(i) == null ? "" : String.valueOf(items.get(i))));
        }
        return out.append('}').toString();
    }

    private String pgArrayElement(String item) {
        boolean quote = item.isEmpty();
        for (int i = 0; i < item.length() && !quote; i++) {
            char c = item.charAt(i);
            quote = c == ',' || c == '"' || c == '\\' || c == '{' || c == '}'
                || Character.isWhitespace(c);
        }
        if (!quote) {
            return item;
        }
        return "\"" + item.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * 恢复顺序：父表先于子表。
     *
     * 只用「不能延后检查」的外键排序。延后检查的那些（skills.latest_version
     * → skill_versions）在提交时才验，插的时候先插哪个都行——
     * 反倒要是拿它排序，skills 会被排到 skill_versions 后面，
     * 而 skill_versions 的 skill_id 又指着 skills，就成了死结。
     */
    List<String> restoreOrder(String schema, Manifest manifest) {
        Set<String> tables = new LinkedHashSet<>();
        for (TableBackup table : manifest.tables()) {
            tables.add(table.table());
        }
        Map<String, Integer> pending = new HashMap<>();
        Map<String, List<String>> children = new HashMap<>();
        for (String table : tables) {
            pending.put(table, 0);
        }
        for (ForeignKey edge : foreignKeys(schema)) {
            // 延后检查的外键不参与排序：它留到提交时才查，
            // 拿它排序会排成死结（skills → skill_versions → skills）。
            if (edge.deferrable() || !tables.contains(edge.parent())
                || !tables.contains(edge.child())) {
                continue;
            }
            pending.put(edge.child(), pending.get(edge.child()) + 1);
            children.computeIfAbsent(edge.parent(), key -> new ArrayList<>()).add(edge.child());
        }
        List<String> order = new ArrayList<>();
        Set<String> done = new HashSet<>();
        // 按清单里的表名顺序取，顺序稳定：同一份备份恢复两次，结果一样。
        for (String table : tables) {
            if (pending.get(table) == 0) {
                order.add(table);
                done.add(table);
            }
        }
        for (int i = 0; i < order.size(); i++) {
            for (String child : children.getOrDefault(order.get(i), List.of())) {
                if (done.contains(child)) {
                    continue;
                }
                int left = pending.get(child) - 1;
                pending.put(child, left);
                if (left == 0) {
                    order.add(child);
                    done.add(child);
                }
            }
        }
        if (order.size() != tables.size()) {
            // 有环：非延后的外键里出现了循环依赖，这个顺序插不进去。
            List<String> missing = new ArrayList<>(tables);
            missing.removeAll(order);
            throw new IllegalStateException("这些表的外键互相依赖，排不出恢复顺序：" + missing);
        }
        return order;
    }

    /** 目标 schema 里的外键。 */
    List<ForeignKey> foreignKeys(String schema) {
        return jdbc.query(
            "select child.relname as child, parent.relname as parent, c.condeferrable "
                + "from pg_constraint c "
                + "join pg_class child on child.oid = c.conrelid "
                + "join pg_class parent on parent.oid = c.confrelid "
                + "join pg_namespace n on n.oid = child.relnamespace "
                + "where c.contype = 'f' and n.nspname = ?",
            (rs, rowNum) -> new ForeignKey(rs.getString("child"), rs.getString("parent"),
                rs.getBoolean("condeferrable")),
            schema);
    }

    /** 每列的转型写法（udt_name）：uuid、timestamptz、jsonb、text[]…… */
    private Map<String, String> castTypesOf(String schema, String table) {
        Map<String, String> types = new HashMap<>();
        jdbc.query("select column_name, udt_name from information_schema.columns "
                + "where table_schema = ? and table_name = ?",
            rs -> {
                String udt = rs.getString("udt_name");
                // 数组列的 udt_name 是 _text，转义写法是 text[]
                types.put(rs.getString("column_name"),
                    udt.startsWith("_") ? udt.substring(1) + "[]" : udt);
            }, schema, table);
        return types;
    }

    /** 目标库里还有数据的表：恢复是往空库里灌，有数据就拒绝。 */
    private List<String> nonEmptyTables(String schema, Manifest manifest) {
        List<String> notEmpty = new ArrayList<>();
        for (TableBackup table : manifest.tables()) {
            if (!tableExists(schema, table.table())) {
                continue;
            }
            Integer count = jdbc.queryForObject(
                "select count(*) from " + quote(schema) + "." + quote(table.table()),
                Integer.class);
            if (count != null && count > 0) {
                notEmpty.add(table.table() + "(" + count + ")");
            }
        }
        return notEmpty;
    }

    private boolean tableExists(String schema, String table) {
        Integer count = jdbc.queryForObject(
            "select count(*) from information_schema.tables "
                + "where table_schema = ? and table_name = ?",
            Integer.class, schema, table);
        return count != null && count > 0;
    }

    /**
     * 自增序列拨到表里最大的那条之后。
     *
     * 不拨的话，恢复完再插新行，主键会撞上备份里已有的那条。
     */
    private void resetSequences(Connection connection, String schema, Manifest manifest)
            throws Exception {
        for (TableBackup table : manifest.tables()) {
            if (!tableExists(schema, table.table())) {
                continue;
            }
            List<String> columns = jdbc.query(
                "select column_name from information_schema.columns "
                    + "where table_schema = ? and table_name = ? and column_default like 'nextval%'",
                (rs, rowNum) -> rs.getString(1), schema, table.table());
            for (String column : columns) {
                String sequence = jdbc.queryForObject(
                    "select pg_get_serial_sequence(?, ?)", String.class,
                    quote(schema) + "." + quote(table.table()), column);
                if (sequence == null) {
                    continue;
                }
                try (Statement statement = connection.createStatement()) {
                    statement.execute("select setval(" + literal(sequence) + ", coalesce((select max("
                        + quote(column) + ") from " + quote(schema) + "." + quote(table.table())
                        + "), 0), false)");
                }
            }
        }
    }

    /** 对象存储：把备份里的对象放回桶里。制品按摘要寻址，重放一次是同内容覆盖。 */
    private int restoreObjects(Path target) throws Exception {
        Manifest manifest = json.readValue(target.resolve("manifest.json").toFile(),
            Manifest.class);
        int count = 0;
        for (ObjectBackup object : manifest.objects()) {
            Path file = target.resolve(object.file());
            if (!Files.exists(file)) {
                // verify 已经报过缺失，这里不再重复失败。
                continue;
            }
            try (InputStream in = Files.newInputStream(file)) {
                minio.putObject(PutObjectArgs.builder()
                    .bucket(bucket).object(object.key())
                    .stream(in, Files.size(file), -1)
                    .contentType("application/octet-stream")
                    .build());
            }
            count++;
        }
        return count;
    }

    private TableBackup tableBackup(Manifest manifest, String table) {
        for (TableBackup backup : manifest.tables()) {
            if (backup.table().equals(table)) {
                return backup;
            }
        }
        return null;
    }

    /** 表名来自 information_schema，但仍过一遍引号，避免关键字表名。 */
    private String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /** 字符串字面量。 */
    private String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    /** 外键：子表、父表，以及这个约束能不能延后检查。 */
    record ForeignKey(String child, String parent, boolean deferrable) {
    }

    /**
     * 一次恢复的结果。
     *
     * 恢复了多少张表、多少行、多少个对象——运维要拿这几行数字对账。
     */
    public record RestoreResult(Path target, String schema, int rows, int objects) {

        public String summary() {
            return "恢复完成：schema " + schema + "，" + rows + " 行、" + objects + " 个对象";
        }
    }
}
