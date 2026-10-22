package com.skillmesh.api.artifact;

import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.ErrorCodes;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 浏览制品里的文件（在线看 Skill 的内部结构）。
 *
 * 两件事：列出有哪些文件、读出某个文件的内容。
 *
 * 清单取**服务端从真 zip 算出来的那份**（{@code artifacts.manifest_json}），
 * 不是发布方在建版本请求里自填的——那份只是他的说法。
 *
 * {@code NOT_SUPPORTED}：下载 zip 不该占着数据库连接。
 */
@Service
public class ArtifactFileService {

    /** 单个文件超过这个大小就不返回内容，只报大小。 */
    public static final int MAX_CONTENT_BYTES = 1 << 20;

    private final ArtifactStorage storage;

    public ArtifactFileService(ArtifactStorage storage) {
        this.storage = storage;
    }

    /**
     * 制品里的文件清单。
     *
     * 取 {@code artifacts.manifest_json}——那是服务端转正制品时从真 zip 算的。
     * 早期入库的制品这条记录可能是空的（清单还没落库），这时从 zip 现场重算：
     * 清单为空就返回空列表，等于告诉用户「这个 Skill 没有文件」——那是假的。
     */
    public List<Map<String, Object>> listFiles(ArtifactEntity artifact) {
        List<Map<String, Object>> manifest = artifact.getManifestJson();
        if (manifest != null && !manifest.isEmpty()) {
            return manifest;
        }
        return recompute(artifact);
    }

    /** 从 zip 现场重算清单。读不出来返回空列表。 */
    private List<Map<String, Object>> recompute(ArtifactEntity artifact) {
        try (InputStream stream = storage.get(artifact.getStorageKey())) {
            return ZipManifest.of(stream.readAllBytes());
        } catch (IOException | IllegalStateException e) {
            // 对象存储读不到或 zip 坏了：没有清单就是没有，不编一个出来。
            return List.of();
        }
    }

    /**
     * 读出一个文件的内容。
     *
     * 二进制文件（图片、字体、可执行文件）不返回内容：给浏览器塞一段乱码
     * 没有意义，还可能被当成脚本渲染。只报它多大。
     *
     * 路径不存在返回 null——调用方据此回 404。
     *
     * @param manifest 已算好的清单。传进来是为了区分「文件不存在」与「文件太大
     *                 被截断」——后者文件是存在的，只是正文没读全。
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Content readContent(ArtifactEntity artifact, List<Map<String, Object>> manifest,
                               String path) {
        byte[] data = readEntry(artifact.getStorageKey(), path);
        if (data == null) {
            boolean exists = manifest.stream()
                .anyMatch(item -> path.equals(String.valueOf(item.get("path"))));
            return exists ? Content.oversized() : null;
        }
        return new Content(data, false);
    }

    /**
     * 读出来的文件内容。
     *
     * @param data 正文。二进制或超限时为 null。
     * @param tooLarge 文件存在但超过上限，正文没读全。
     */
    public record Content(byte[] data, boolean tooLarge) {
        static Content oversized() {
            return new Content(null, true);
        }
    }

    /** 文件是不是二进制（不能当文本显示）。 */
    public static boolean isBinary(byte[] data) {
        int limit = Math.min(data.length, 8000);
        for (int i = 0; i < limit; i++) {
            int b = data[i] & 0xFF;
            // 文本里不该出现的控制字符（制表、换行、回车除外）
            if (b < 0x09 || (b > 0x0D && b < 0x20) || b == 0x7F) {
                return true;
            }
        }
        return false;
    }

    public static String toText(byte[] data) {
        return new String(data, StandardCharsets.UTF_8);
    }

    /**
     * 从 zip 里取一个 entry 的字节。取不到返回 null。
     *
     * 重名 entry 取**最后一个**：CLI 安装器也是后一个覆盖前一个，
     * 清单也是后一个覆盖前一个——面板显示的必须是客户端真会装的那一份。
     */
    private byte[] readEntry(String storageKey, String path) {
        try (InputStream stream = storage.get(storageKey);
             ZipInputStream zip = new ZipInputStream(stream)) {
            byte[] found = null;
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory() || !path.equals(entry.getName())) {
                    continue;
                }
                found = readCapped(zip);
            }
            return found;
        } catch (IOException | IllegalStateException e) {
            // IOException：zip 坏了。IllegalStateException：对象存储读不到。
            return null;
        }
    }

    /** 读一个 entry 的内容；超过上限返回 null。 */
    private byte[] readCapped(ZipInputStream zip) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = zip.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
            if (buffer.size() > MAX_CONTENT_BYTES) {
                return null;
            }
        }
        return buffer.toByteArray();
    }
}
