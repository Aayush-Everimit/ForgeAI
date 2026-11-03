package com.forgeai.shared.repository;

import com.forgeai.shared.domain.SourceDocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SourceDocumentRepository extends JpaRepository<SourceDocumentEntity, UUID> {
    List<SourceDocumentEntity> findByProjectIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID projectId);
    Optional<SourceDocumentEntity> findByIdAndProjectIdAndDeletedAtIsNull(UUID id, UUID projectId);
    List<SourceDocumentEntity> findByProjectId(UUID projectId);
}
