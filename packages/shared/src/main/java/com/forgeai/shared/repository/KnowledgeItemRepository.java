package com.forgeai.shared.repository;

import com.forgeai.shared.domain.KnowledgeItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KnowledgeItemRepository extends JpaRepository<KnowledgeItemEntity, UUID> {
    List<KnowledgeItemEntity> findByProjectIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID projectId);
    List<KnowledgeItemEntity> findByProjectIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(UUID projectId, String status);
    Optional<KnowledgeItemEntity> findByIdAndProjectIdAndDeletedAtIsNull(UUID id, UUID projectId);
}
