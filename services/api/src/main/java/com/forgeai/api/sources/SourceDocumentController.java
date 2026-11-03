package com.forgeai.api.sources;

import com.forgeai.api.common.ApiException;
import com.forgeai.api.security.ProjectAccessGuard;
import com.forgeai.shared.domain.JobEntity;
import com.forgeai.shared.domain.SourceDocumentEntity;
import com.forgeai.shared.repository.SourceDocumentRepository;
import com.forgeai.shared.service.AuditService;
import com.forgeai.shared.service.DurableJobQueueService;
import com.forgeai.shared.util.HashUtil;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/sources")
public class SourceDocumentController {

    private final SourceDocumentRepository sourceRepository;
    private final ProjectAccessGuard projectAccessGuard;
    private final DurableJobQueueService jobQueueService;
    private final AuditService auditService;

    public SourceDocumentController(
            SourceDocumentRepository sourceRepository,
            ProjectAccessGuard projectAccessGuard,
            DurableJobQueueService jobQueueService,
            AuditService auditService
    ) {
        this.sourceRepository = sourceRepository;
        this.projectAccessGuard = projectAccessGuard;
        this.jobQueueService = jobQueueService;
        this.auditService = auditService;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<SourceDocumentResponse> listSources(@PathVariable UUID projectId) {
        projectAccessGuard.requireOwnedProject(projectId);
        return sourceRepository.findByProjectIdAndDeletedAtIsNullOrderByCreatedAtDesc(projectId)
                .stream()
                .map(s -> toResponse(s, null))
                .toList();
    }

    @PostMapping
    @Transactional
    public ResponseEntity<SourceDocumentResponse> registerSource(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateSourceRequest request
    ) {
        ProjectAccessGuard.AuthorizedProjectContext ctx = projectAccessGuard.requireOwnedProject(projectId);
        SourceDocumentEntity source = new SourceDocumentEntity();
        source.setProjectId(projectId);
        source.setSourceType(request.sourceType().trim().toUpperCase());
        source.setSourceReference(request.sourceReference().trim());
        source.setContent(request.content());
        source.setContentHash(HashUtil.sha256Hex(request.content()));
        source.setSourceVersion(1L);
        source.setIngestionStatus("PENDING");
        SourceDocumentEntity saved = sourceRepository.save(source);

        JobEntity ingestJob = jobQueueService.enqueueIdempotent(
                projectId,
                "SOURCE_INGESTION",
                "SOURCE",
                saved.getId(),
                saved.getSourceVersion(),
                "source-ingest:" + saved.getId() + ":v" + saved.getSourceVersion(),
                "{\"sourceId\":\"" + saved.getId() + "\"}",
                Instant.now()
        );

        auditService.record(ctx.user().getId(), projectId, "SOURCE_REGISTERED", "SOURCE", saved.getId(), null);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(toResponse(saved, ingestJob.getId()));
    }

    @DeleteMapping("/{sourceId}")
    @Transactional
    public ResponseEntity<DeleteSourceResponse> deleteSource(
            @PathVariable UUID projectId,
            @PathVariable UUID sourceId
    ) {
        ProjectAccessGuard.AuthorizedProjectContext ctx = projectAccessGuard.requireOwnedProject(projectId);
        SourceDocumentEntity source = sourceRepository.findById(sourceId)
                .filter(s -> s.getProjectId().equals(projectId))
                .orElseThrow(() -> ApiException.notFound("SOURCE_NOT_FOUND", "Source document not found"));

        if (source.getDeletedAt() == null) {
            source.setDeletedAt(Instant.now());
            source.setIngestionStatus("DELETING");
            // Increment sourceVersion so any in-flight SOURCE_INGESTION job sees a stale generation and aborts
            source.setSourceVersion(source.getSourceVersion() + 1L);
            sourceRepository.save(source);
        }

        JobEntity cleanupJob = jobQueueService.enqueueIdempotent(
                projectId,
                "SOURCE_DELETION",
                "SOURCE",
                source.getId(),
                source.getSourceVersion(),
                "source-delete:" + source.getId() + ":v" + source.getSourceVersion(),
                "{\"sourceId\":\"" + source.getId() + "\"}",
                Instant.now()
        );

        auditService.record(ctx.user().getId(), projectId, "SOURCE_DELETED", "SOURCE", source.getId(), null);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new DeleteSourceResponse("DELETING", source.getId(), cleanupJob.getId()));
    }

    private SourceDocumentResponse toResponse(SourceDocumentEntity entity, UUID jobId) {
        return new SourceDocumentResponse(
                entity.getId(),
                entity.getProjectId(),
                entity.getSourceType(),
                entity.getSourceReference(),
                entity.getContentHash(),
                entity.getSourceVersion(),
                entity.getIngestionStatus(),
                jobId,
                entity.getLastIndexedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    public record CreateSourceRequest(
            @NotBlank(message = "Source type is required")
            String sourceType,
            @NotBlank(message = "Source reference is required")
            @Size(max = 500)
            String sourceReference,
            @NotBlank(message = "Source content is required")
            @Size(max = 200000)
            String content
    ) {}

    public record SourceDocumentResponse(
            UUID id,
            UUID projectId,
            String sourceType,
            String sourceReference,
            String contentHash,
            long sourceVersion,
            String ingestionStatus,
            UUID jobId,
            Instant lastIndexedAt,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record DeleteSourceResponse(
            String status,
            UUID sourceId,
            UUID cleanupJobId
    ) {}
}
