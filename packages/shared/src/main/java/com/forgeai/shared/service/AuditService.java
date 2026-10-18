package com.forgeai.shared.service;

import com.forgeai.shared.domain.AuditEventEntity;
import com.forgeai.shared.repository.AuditEventRepository;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AuditService {

    private final AuditEventRepository auditEventRepository;

    public AuditService(AuditEventRepository auditEventRepository) {
        this.auditEventRepository = auditEventRepository;
    }

    @Transactional
    public AuditEventEntity record(UUID actorUserId, UUID projectId, String eventType, String entityType, UUID entityId, String safeMetadataJson) {
        AuditEventEntity event = new AuditEventEntity();
        event.setActorUserId(actorUserId);
        event.setProjectId(projectId);
        event.setEventType(eventType);
        event.setEntityType(entityType);
        event.setEntityId(entityId);
        event.setRequestId(MDC.get("requestId"));
        event.setMetadataJson(safeMetadataJson);
        return auditEventRepository.save(event);
    }
}
