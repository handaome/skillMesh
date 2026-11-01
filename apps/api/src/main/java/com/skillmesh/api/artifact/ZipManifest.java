package com.skillmesh.api.artifact;

import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.ContentDigest;
import com.skillmesh.api.common.ErrorCodes;
import com.skillmesh.api.common.PathOrder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;

/**
 * 从真 zip 算逐文件清单（路径 + 每个文件的 sha256）。
 *
 * 口径与 CLI 的 packager 一致：entry 名就是相对路径（正斜杠），
 * 摘要算的是文件内容而不是压缩后的字节，清单按路径排序。
 * 两边一致才能把服务端算的清单和 CLI 报的清单放在一起比。
 */
public final class ZipManifest {

    /** 制品里最多这么多 entry。清单整份存进 jsonb，条数没上限能把库撑爆。 */
    public static final int MAX_ENTRIES = 10_000;

    /** zip 本地文件头的魔数（PK\x03\x04）。 */
    private static final byte[] ZIP_MAGIC = {0x50, 0x4B, 0x03, 0x04};

    /** 解压后的总字节上限。压缩包本身有 50MB 上限，解压后可以大出几十倍。 */
    public static final long MAX_TOTAL_BYTES = 200L << 20;

    /**
     * 单个文件解压后的上限。与扫描器（{@code ArtifactScanner}）同一个数：
     * 扫描器按这个数拒，服务端就别先把这么大的东西读进堆里。
     */
    public static final long MAX_FILE_BYTES = 20L << 20;

    /** 解压后总量与归档字节数的比值上限。超过就是压缩炸弹。 */
    public static final long MAX_RATIO = 100;

    /** 比值只在这个总量以上才判。小文件压缩比高是正常的，一段 1KB 的文本能压到几十字节。 */
    private static final long RATIO_MIN_BYTES = 1L << 20;

    /**
     * 归档安全检查：符号链接与压缩炸弹。
     *
     * 符号链接能指向包外的路径，装到 Agent 目录时一解析就写出去了。
     * 客户端安装器自己也拦（跟链接但不许跟出包外），这里是服务端那道闸——
     * 绕过 CLI 直传 API 的上传同样要过。
     *
     * 压缩炸弹是另一种：包本身几 KB，解开几百 MB，把 CPU 和磁盘打满。
     * 光卡总量不够，因为总量上限要等解压完才生效，而解压本身就要花时间。
     *
     * 读 central directory 而不是流式读：Go 的 zip writer 只把 unix 权限位写进
     * central directory，本地文件头里没有，流式解析拿不到符号链接标记。
     */
    public static void requireSafeArchive(byte[] zip) {
        long uncompressed = 0;
        try (ZipFile archive = new ZipFile(new SeekableInMemoryByteChannel(zip), "UTF-8")) {
            Enumeration<ZipArchiveEntry> entries = archive.getEntriesInPhysicalOrder();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                int mode = entry.getUnixMode();
                if ((mode & UnixStat.FILE_TYPE_FLAG) == UnixStat.LINK_FLAG) {
                    throw new ApiException(422, ErrorCodes.VALIDATION_FAILED,
                        "制品里不能有符号链接：" + entry.getName());
                }
                uncompressed += Math.max(0L, entry.getSize());
            }
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw new ApiException(422, ErrorCodes.VALIDATION_FAILED,
                "读 zip 归档失败：" + e.getMessage());
        }
        long archiveBytes = Math.max(1, zip.length);
        if (uncompressed > RATIO_MIN_BYTES && uncompressed / archiveBytes > MAX_RATIO) {
            throw new ApiException(422, ErrorCodes.VALIDATION_FAILED,
                "制品解压后是压缩前的 " + (uncompressed / archiveBytes) + " 倍，超出上限 "
                    + MAX_RATIO + " 倍");
        }
    }

    private ZipManifest() {}

    /**
     * 确认这批字节是一份 zip 归档。
     *
     * Java 的 ZipInputStream 对不是 zip 的字节**不报错**：它扫不到本地文件头就
     * 直接返回空清单。于是一份 tar.gz、一段 JSON 错误页，都能被当成「空制品」
     * 收下、发布出去，直到客户端安装时才炸（Go 的 archive/zip 是严格的，直接报
     * not a valid zip file）。发布这道闸得自己把住。
     */
    public static void requireZip(byte[] bytes) {
        if (bytes.length >= ZIP_MAGIC.length
            && bytes[0] == ZIP_MAGIC[0] && bytes[1] == ZIP_MAGIC[1]
            && bytes[2] == ZIP_MAGIC[2] && bytes[3] == ZIP_MAGIC[3]) {
            return;
        }
        throw new ApiException(422, ErrorCodes.VALIDATION_FAILED,
            "上传内容不是 zip 归档：制品必须是 zip（收到的是 " + describeFormat(bytes) + "）");
    }

    /**
     * 清单里有没有 Skill 根。
     *
     * 口径与 CLI 安装器的 findSkillRoot 一致：根目录下的 SKILL.md，
     * 或者只包了一层目录（{@code <目录>/SKILL.md}）。
     */
    public static boolean hasSkillRoot(List<Map<String, Object>> manifest) {
        if (find(manifest, "SKILL.md") != null) {
            return true;
        }
        for (Map<String, Object> item : manifest) {
            String path = String.valueOf(item.get("path"));
            int slash = path.indexOf('/');
            if (slash > 0 && path.indexOf('/', slash + 1) < 0 && path.endsWith("/SKILL.md")) {
                return true;
            }
        }
        return false;
    }

    /** 说清收到的是什么格式，省得用户对着一个 422 猜。 */
    private static String describeFormat(byte[] bytes) {
        if (bytes.length == 0) {
            return "空内容";
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B) {
            return "gzip 压缩包（tar.gz？）";
        }
        if (bytes.length >= 4 && bytes[0] == '<' && bytes[1] == '!' && bytes[2] == 'D'
            && bytes[3] == 'O') {
            return "HTML 页面";
        }
        return "非 zip 字节";
    }

    /**
     * 逐文件算摘要，按路径排序。
     *
     * 目录项跳过（CLI 打包时不写目录项）；文件内容按原样算摘要。
     *
     * 重名 entry 是非法制品，直接拒：清单取最后一个、{@code readEntry} 取第一个、
     * CLI 安装器取最后一个——三者对同一路径可能指向不同字节，
     * 审核面板显示一份、客户端装到另一份，审核队列这道闸就被绕过了。
     */
    public static List<Map<String, Object>> of(byte[] zip) {
        List<Map<String, Object>> manifest = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        long total = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip),
                StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (!seen.add(entry.getName())) {
                    throw new ApiException(422, ErrorCodes.VALIDATION_FAILED,
                        "制品里有重名的文件：" + entry.getName());
                }
                if (seen.size() > MAX_ENTRIES) {
                    throw new ApiException(422, ErrorCodes.VALIDATION_FAILED,
                        "制品里的文件数超过上限 " + MAX_ENTRIES);
                }
                if (entry.isDirectory()) {
                    continue;
                }
                byte[] data = readAll(in, Math.min(MAX_FILE_BYTES, MAX_TOTAL_BYTES - total), entry.getName());
                total += data.length;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("path", entry.getName());
                item.put("sha256", ContentDigest.sha256(data));
                item.put("size_bytes", (long) data.length);
                manifest.add(item);
            }
        } catch (IOException e) {
            throw new IllegalStateException("读 zip 失败", e);
        }
        manifest.sort(Comparator.comparing(left -> String.valueOf(left.get("path")),
            PathOrder.BY_CODE_POINT));
        return manifest;
    }

    /**
     * 在清单里按路径找条目。找不到返回 null。
     *
     * 路径是清单的主键：一个包里同一路径只对应一个文件（重名 entry 已经被
     * {@link #of} 拒了）。所有「按路径查条目」的地方都走它，别再各写一遍遍历。
     */
    public static Map<String, Object> find(List<Map<String, Object>> manifest, String path) {
        for (Map<String, Object> item : manifest) {
            if (path.equals(String.valueOf(item.get("path")))) {
                return item;
            }
        }
        return null;
    }

    /** 读一个 entry 的内容。剩下的额度不够读完就是超限，直接拒。 */
    private static byte[] readAll(ZipInputStream in, long budget, String path) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) {
            if (out.size() + (long) read > budget) {
                throw new ApiException(422, ErrorCodes.VALIDATION_FAILED,
                    "制品里的 " + path + " 超过大小上限 " + budget + " 字节");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
