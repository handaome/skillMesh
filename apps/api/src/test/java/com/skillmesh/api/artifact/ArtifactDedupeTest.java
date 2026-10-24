package com.skillmesh.api.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 同一份内容在桶里只留一份。
 *
 * 原来的正式对象 key 里带着 skillId 与版本号：同一份内容发到两个版本，
 * 会算出两个不同的 key，桶里各存一份——而库里只有一条 artifact 记录，
 * 第二个对象成了没人引用的孤儿，还清不掉（生命周期规则只清 tmp/ 前缀）。
 *
 * 现在正式对象按内容寻址（`sha256/<摘要>.zip`），落桶前先查已有记录，
 * 命中就复用它的 key，不写第二份。
 */
class ArtifactDedupeTest {

    @Test
    void 同一份内容重复上传只写一次桶() {
        ArtifactStorage storage = mock(ArtifactStorage.class);
        ArtifactRepository artifacts = mock(ArtifactRepository.class);
        ArtifactService service = new ArtifactService(storage, artifacts);

        byte[] zip = "zip".getBytes(StandardCharsets.UTF_8);
        String sha = ArtifactService.sha256(zip);

        // 第一次上传：桶里还没有这份内容。
        when(storage.exists("tmp/1")).thenReturn(true);
        when(storage.exists("tmp/2")).thenReturn(true);
        when(storage.size(anyString())).thenReturn((long) zip.length);
        // 每次读都要给一个新流：一个流读完就没了，第二次 promote 会读到空。
        when(storage.get(anyString())).thenAnswer(
            ignored -> new java.io.ByteArrayInputStream(zip));
        when(artifacts.findBySha256(sha))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(existingArtifact(sha)));

        var first = service.promote("tmp/1", sha, zip.length);
        var second = service.promote("tmp/2", sha, zip.length);

        // 两次算出同一个 key：桶里是一份内容，不是两份。
        assertThat(second.storageKey()).as("同一份内容落在同一个 key")
            .isEqualTo(first.storageKey());
        assertThat(first.storageKey()).isEqualTo("sha256/" + sha + ".zip");
    }

    /** 库里已有这条记录、对象也还在时：复用它的 key，一个字节都不用重写。 */
    @Test
    void 已有记录时复用原来的key() {
        ArtifactStorage storage = mock(ArtifactStorage.class);
        ArtifactRepository artifacts = mock(ArtifactRepository.class);
        ArtifactService service = new ArtifactService(storage, artifacts);

        byte[] zip = "zip".getBytes(StandardCharsets.UTF_8);
        String sha = ArtifactService.sha256(zip);

        when(storage.exists("tmp/1")).thenReturn(true);
        // 正式对象也还在：不用补写。
        when(storage.exists("sha256/" + sha + ".zip")).thenReturn(true);
        when(storage.size(anyString())).thenReturn((long) zip.length);
        when(storage.get(anyString())).thenAnswer(
            ignored -> new java.io.ByteArrayInputStream(zip));
        when(artifacts.findBySha256(sha)).thenReturn(Optional.of(existingArtifact(sha)));

        var promoted = service.promote("tmp/1", sha, zip.length);

        assertThat(promoted.storageKey())
            .as("复用已有记录的 key").isEqualTo("sha256/" + sha + ".zip");
        // 对象还在，压根不用重写。
        verify(storage, times(0)).put(anyString(), any(), anyString());
    }

    private ArtifactEntity existingArtifact(String sha) {
        ArtifactEntity entity = new ArtifactEntity();
        entity.setId(java.util.UUID.randomUUID());
        entity.setSha256(sha);
        entity.setStorageKey("sha256/" + sha + ".zip");
        entity.setContentType("application/zip");
        entity.setSizeBytes(3);
        entity.setFileCount(1);
        return entity;
    }
}
