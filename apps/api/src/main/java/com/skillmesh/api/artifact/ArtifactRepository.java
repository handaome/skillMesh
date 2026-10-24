package com.skillmesh.api.artifact;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ArtifactRepository extends JpaRepository<ArtifactEntity, UUID> {

    Optional<ArtifactEntity> findBySha256(String sha256);

    /** 库里引用到的所有对象 key：桶里的对象不在其中就是孤儿。 */
    @Query("select a.storageKey from ArtifactEntity a")
    List<String> findAllStorageKeys();

    /**
     * 插一条制品记录，内容相同就别插。
     *
     * sha256 上有唯一约束：两台设备同时提交同一份内容时，
     * 「先查再插」会双双查到空，然后第二个插入撞约束变成 500。
     * 这里让数据库判：撞上了就什么都不做，调用方再读一次读到的就是那条。
     *
     * 冲突目标不指定：storage_key 也有唯一约束，一并让掉，
     * 免得撞它时整条语句失败变成 500。
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
        insert into artifacts (id, sha256, storage_key, content_type, size_bytes,
                               file_count, manifest_json, created_at)
        values (:id, :sha256, :storageKey, 'application/zip', :sizeBytes,
                :fileCount, cast(:manifestJson as jsonb), now())
        on conflict do nothing
        """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id,
                       @Param("sha256") String sha256,
                       @Param("storageKey") String storageKey,
                       @Param("sizeBytes") long sizeBytes,
                       @Param("fileCount") int fileCount,
                       @Param("manifestJson") String manifestJson);
}
