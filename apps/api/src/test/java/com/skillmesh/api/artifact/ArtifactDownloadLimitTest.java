package com.skillmesh.api.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillmesh.api.common.ApiException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/**
 * 下载并发上限（设计文档 §4.1「下载并发限制」）。
 *
 * 名额满了要拒，而且名额得跟着流走——流关了就还回来，
 * 不然下载几次之后谁也下不动。
 */
class ArtifactDownloadLimitTest {

    private final ArtifactStorage storage = mock(ArtifactStorage.class);
    private final ArtifactRepository artifacts = mock(ArtifactRepository.class);

    private ArtifactService service(int maxConcurrent) {
        return new ArtifactService(storage, artifacts, new ObjectMapper(),
            ArtifactService.DEFAULT_MAX_ARTIFACT_BYTES, maxConcurrent);
    }

    private void 存储里有制品() {
        when(storage.get("sha256/x.zip")).thenAnswer(invocation ->
            (InputStream) new ByteArrayInputStream(new byte[]{1, 2, 3}));
    }

    @Test
    void 名额满了拒掉后来的下载() {
        存储里有制品();
        ArtifactService service = service(1);
        ArtifactEntity artifact = new ArtifactEntity();
        artifact.setStorageKey("sha256/x.zip");

        InputStream first = service.openDownload(artifact);
        assertThatThrownBy(() -> service.openDownload(artifact))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(429));
        assertThat(first).isNotNull();
    }

    @Test
    void 流关掉之后名额还回来() throws Exception {
        存储里有制品();
        ArtifactService service = service(1);
        ArtifactEntity artifact = new ArtifactEntity();
        artifact.setStorageKey("sha256/x.zip");

        try (InputStream first = service.openDownload(artifact)) {
            assertThat(first.read()).isEqualTo(1);
        }
        // 名额还回来了，这次能拿到。
        try (InputStream second = service.openDownload(artifact)) {
            assertThat(second.read()).isEqualTo(1);
        }
    }
}
