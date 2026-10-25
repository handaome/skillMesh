package com.skillmesh.api.upload;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface UploadSessionRepository extends JpaRepository<UploadSessionEntity, UUID> {

    /**
     * 按变更集取已登记的上传会话。
     *
     * purpose 必须带上：uq_sync_upload_path 是部分索引（WHERE purpose = 'sync_file'），
     * 查询不带这个条件，PostgreSQL 不会用它，只能全表扫。
     */
    List<UploadSessionEntity> findByChangesetIdAndPurposeOrderByRelativePathAsc(
        UUID changesetId, String purpose);

    /**
     * 清掉过期会话行。对象存储那边临时对象按 SM_MINIO_TEMP_EXPIRE_DAYS 自动过期，
     * 这张表没有对应的清理——不删就会一直涨。
     */
    @Modifying
    @Transactional
    @Query("delete from UploadSessionEntity u where u.expiresAt <= :now")
    int deleteExpired(@Param("now") OffsetDateTime now);
}
