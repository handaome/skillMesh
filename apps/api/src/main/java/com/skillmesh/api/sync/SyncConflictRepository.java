package com.skillmesh.api.sync;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncConflictRepository extends JpaRepository<SyncConflictEntity, UUID> {

    List<SyncConflictEntity> findByChangesetIdOrderByCreatedAtAsc(UUID changesetId);
}
