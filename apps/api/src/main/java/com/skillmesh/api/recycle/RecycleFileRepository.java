package com.skillmesh.api.recycle;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecycleFileRepository extends JpaRepository<RecycleFileEntity, UUID> {

    /**
     * 回收站里还没过期的文件。
     *
     * 条件写出来而不是靠派生查询名：「过期」判的是 expires_at 大于现在，
     * 回收期到了的条目不该出现在列表里。
     */
    @Query("select r from RecycleFileEntity r where r.skillId = :skillId "
        + "and r.expiresAt > :now order by r.createdAt desc")
    List<RecycleFileEntity> findActive(@Param("skillId") UUID skillId,
                                       @Param("now") OffsetDateTime now);

    Optional<RecycleFileEntity> findBySkillIdAndPath(UUID skillId, String path);

    /** 回收期到了的：真删掉，不再能恢复。 */
    @Modifying
    @Query("delete from RecycleFileEntity r where r.expiresAt <= :now")
    int deleteExpired(@Param("now") OffsetDateTime now);
}
