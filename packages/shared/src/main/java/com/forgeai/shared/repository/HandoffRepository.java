package com.forgeai.shared.repository;

import com.forgeai.shared.domain.HandoffEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HandoffRepository extends JpaRepository<HandoffEntity, UUID> {
    List<HandoffEntity> findByProjectIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID projectId);
    Optional<HandoffEntity> findByIdAndProjectIdAndDeletedAtIsNull(UUID id, UUID projectId);
}
