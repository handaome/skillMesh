package com.skillmesh.api.upload;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UploadSessionRepository extends JpaRepository<UploadSessionEntity, UUID> {
}
