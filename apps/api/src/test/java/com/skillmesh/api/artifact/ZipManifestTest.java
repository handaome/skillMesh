package com.skillmesh.api.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.ErrorCodes;
import com.skillmesh.api.support.TestArtifacts;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 从真 zip 算清单时的校验。
 *
 * 重名 entry 必须拒：清单取最后一个、审核面板取第一个、CLI 安装器取最后一个，
 * 三者可能指向不同字节——审核者看到一份、客户端装到另一份，审核队列就被绕过了。
 */
class ZipManifestTest {

    @Test
    void 重名entry的包要拒() throws Exception {
        byte[] zip = TestArtifacts.zipWithDuplicateEntry("SKILL.md", "# A\n", "# MALICIOUS\n");

        ApiException error = catchThrowableOfType(() -> ZipManifest.of(zip), ApiException.class);

        assertThat(error).isNotNull();
        assertThat(error.status()).isEqualTo(422);
        assertThat(error.code()).isEqualTo(ErrorCodes.VALIDATION_FAILED);
        assertThat(error.getMessage()).contains("SKILL.md");
    }

    @Test
    void 文件数超上限要拒() throws Exception {
        byte[] zip = TestArtifacts.zipOfEmptyEntries(ZipManifest.MAX_ENTRIES + 1);

        ApiException error = catchThrowableOfType(() -> ZipManifest.of(zip), ApiException.class);

        assertThat(error).isNotNull();
        assertThat(error.status()).isEqualTo(422);
        assertThat(error.getMessage()).contains("上限");
    }

    @Test
    void 正常的包照常算清单() throws Exception {
        byte[] zip = TestArtifacts.zipOf("SKILL.md", "# A\n");

        List<Map<String, Object>> manifest = ZipManifest.of(zip);

        assertThat(manifest).hasSize(1);
        assertThat(manifest.get(0).get("path")).isEqualTo("SKILL.md");
        assertThat(manifest.get(0).get("sha256"))
            .isEqualTo(TestArtifacts.sha256("# A\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
