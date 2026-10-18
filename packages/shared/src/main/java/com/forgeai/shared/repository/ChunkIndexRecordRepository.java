package com.forgeai.shared.repository;

import com.forgeai.shared.domain.ChunkIndexRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ChunkIndexRecordRepository extends JpaRepository<ChunkIndexRecordEntity, UUID> {
    List<ChunkIndexRecordEntity> findBySourceId(UUID sourceId);
    List<ChunkIndexRecordEntity> findByProjectId(UUID projectId);
    void deleteBySourceId(UUID sourceId);
    void deleteByProjectId(UUID projectId);
}
