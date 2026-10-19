package com.skillmesh.api.upload;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UploadSessionRepository extends JpaRepository<UploadSessionEntity, UUID> {

    List<UploadSessionEntity> findByChangesetIdOrderByRelativePathAsc(UUID changesetId);
}
