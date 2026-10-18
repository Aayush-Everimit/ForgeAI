package com.forgeai.shared.repository;

import com.forgeai.shared.domain.AuditEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEventEntity, UUID> {
    List<AuditEventEntity> findByProjectIdOrderByCreatedAtDesc(UUID projectId);
}
