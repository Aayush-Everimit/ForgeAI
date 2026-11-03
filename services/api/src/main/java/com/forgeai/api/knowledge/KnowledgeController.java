package com.forgeai.api.knowledge;

import com.forgeai.api.common.ApiException;
import com.forgeai.api.security.ProjectAccessGuard;
import com.forgeai.shared.domain.KnowledgeItemEntity;
import com.forgeai.shared.repository.KnowledgeItemRepository;
import com.forgeai.shared.service.AuditService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/knowledge")
public class KnowledgeController {

    private static final Set<String> ALLOWED_KINDS = Set.of(
            "NOTE", "PROGRESS", "DECISION", "CONSTRAINT", "ISSUE", "SUMMARY"
    );

    private final KnowledgeItemRepository knowledgeRepository;
    private final ProjectAccessGuard projectAccessGuard;
    private final AuditService auditService;

    public KnowledgeController(
            KnowledgeItemRepository knowledgeRepository,
            ProjectAccessGuard projectAccessGuard,
            AuditService auditService
    ) {
        this.knowledgeRepository = knowledgeRepository;
        this.projectAccessGuard = projectAccessGuard;
        this.auditService = auditService;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<KnowledgeItemResponse> listKnowledge(
            @PathVariable UUID projectId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String kind
    ) {
        projectAccessGuard.requireOwnedProject(projectId);
        List<KnowledgeItemEntity> items = (status != null && !status.isBlank())
                ? knowledgeRepository.findByProjectIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(projectId, status.toUpperCase())
                : knowledgeRepository.findByProjectIdAndDeletedAtIsNullOrderByCreatedAtDesc(projectId);

        if (kind != null && !kind.isBlank()) {
            String normalizedKind = kind.toUpperCase();
            items = items.stream().filter(i -> normalizedKind.equals(i.getKind())).toList();
        }
        return items.stream().map(i -> toResponse(i, List.of())).toList();
    }

    @PostMapping
    @Transactional
    public ResponseEntity<KnowledgeItemResponse> createKnowledge(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateKnowledgeRequest request
    ) {
        ProjectAccessGuard.AuthorizedProjectContext ctx = projectAccessGuard.requireOwnedProject(projectId);
        String normalizedKind = request.kind().trim().toUpperCase();
        if (!ALLOWED_KINDS.contains(normalizedKind)) {
            throw ApiException.badRequest("INVALID_KNOWLEDGE_KIND", "Unsupported knowledge kind: " + request.kind());
        }

        String sourceType = (request.sourceType() == null || request.sourceType().isBlank())
                ? "MANUAL"
                : request.sourceType().trim().toUpperCase();

        // AI proposals are always created as PROPOSED so the user reviews/edits/approves them explicitly (FR-06)
        boolean autoApprove = !"AI_PROPOSAL".equals(sourceType) && Boolean.TRUE.equals(request.autoApprove());

        // Surface conflict warnings against existing approved items of the same kind (FR-17)
        List<String> conflictWarnings = new ArrayList<>();
        List<KnowledgeItemEntity> existingApproved = knowledgeRepository
                .findByProjectIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(projectId, "APPROVED");
        for (KnowledgeItemEntity existing : existingApproved) {
            if (existing.getKind().equals(normalizedKind)
                    && existing.getTitle().equalsIgnoreCase(request.title().trim())) {
                conflictWarnings.add("CONFLICT_DETECTED: Approved " + normalizedKind + " item '"
                        + existing.getTitle() + "' (id=" + existing.getId() + ") already exists. Use review with supersedesItemId to version it safely.");
            }
        }

        KnowledgeItemEntity item = new KnowledgeItemEntity();
        item.setProjectId(projectId);
        item.setKind(normalizedKind);
        item.setTitle(request.title().trim());
        item.setContent(request.content().trim());
        item.setSourceType(sourceType);
        item.setSourceReference(request.sourceReference());
        item.setContentHash(HashUtil.sha256Hex(item.getTitle() + "\n" + item.getContent()));
        item.setVersion(1L);

        if (autoApprove && conflictWarnings.isEmpty()) {
            item.setStatus("APPROVED");
            item.setApprovedBy(ctx.user().getId());
            item.setApprovedAt(Instant.now());
        } else {
            item.setStatus("PROPOSED");
        }

        KnowledgeItemEntity saved = knowledgeRepository.save(item);
        auditService.record(ctx.user().getId(), projectId, "KNOWLEDGE_" + saved.getStatus(), "KNOWLEDGE", saved.getId(), null);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(saved, conflictWarnings));
    }

    @PostMapping("/{itemId}/review")
    @Transactional
    public KnowledgeItemResponse reviewKnowledge(
            @PathVariable UUID projectId,
            @PathVariable UUID itemId,
            @Valid @RequestBody ReviewKnowledgeRequest request
    ) {
        ProjectAccessGuard.AuthorizedProjectContext ctx = projectAccessGuard.requireOwnedProject(projectId);
        KnowledgeItemEntity item = knowledgeRepository.findByIdAndProjectIdAndDeletedAtIsNull(itemId, projectId)
                .orElseThrow(() -> ApiException.notFound("KNOWLEDGE_NOT_FOUND", "Knowledge item not found"));

        String action = request.action().trim().toUpperCase();
        if (!"APPROVE".equals(action) && !"REJECT".equals(action)) {
            throw ApiException.badRequest("INVALID_REVIEW_ACTION", "Review action must be APPROVE or REJECT");
        }

        if (request.editedTitle() != null && !request.editedTitle().isBlank()) {
            item.setTitle(request.editedTitle().trim());
        }
        if (request.editedContent() != null && !request.editedContent().isBlank()) {
            item.setContent(request.editedContent().trim());
        }
        item.setContentHash(HashUtil.sha256Hex(item.getTitle() + "\n" + item.getContent()));
        item.setReviewNotes(request.reviewNotes());
        item.setVersion(item.getVersion() + 1L);

        if ("APPROVE".equals(action)) {
            if (request.supersedesItemId() != null) {
                KnowledgeItemEntity superseded = knowledgeRepository
                        .findByIdAndProjectIdAndDeletedAtIsNull(request.supersedesItemId(), projectId)
                        .orElseThrow(() -> ApiException.notFound("SUPERSEDED_ITEM_NOT_FOUND", "Superseded knowledge item not found"));
                superseded.setStatus("SUPERSEDED");
                superseded.setVersion(superseded.getVersion() + 1L);
                knowledgeRepository.save(superseded);
                item.setSupersedesId(superseded.getId());
            }
            item.setStatus("APPROVED");
            item.setApprovedBy(ctx.user().getId());
            item.setApprovedAt(Instant.now());
        } else {
            item.setStatus("REJECTED");
        }

        KnowledgeItemEntity saved = knowledgeRepository.save(item);
        auditService.record(ctx.user().getId(), projectId, "KNOWLEDGE_REVIEW_" + action, "KNOWLEDGE", saved.getId(), null);
        return toResponse(saved, List.of());
    }

    @DeleteMapping("/{itemId}")
    @Transactional
    public ResponseEntity<Void> deleteKnowledge(@PathVariable UUID projectId, @PathVariable UUID itemId) {
        ProjectAccessGuard.AuthorizedProjectContext ctx = projectAccessGuard.requireOwnedProject(projectId);
        knowledgeRepository.findByIdAndProjectIdAndDeletedAtIsNull(itemId, projectId).ifPresent(item -> {
            item.setDeletedAt(Instant.now());
            item.setStatus("DELETED");
            item.setVersion(item.getVersion() + 1L);
            knowledgeRepository.save(item);
            auditService.record(ctx.user().getId(), projectId, "KNOWLEDGE_DELETED", "KNOWLEDGE", item.getId(), null);
        });
        return ResponseEntity.noContent().build();
    }

    private KnowledgeItemResponse toResponse(KnowledgeItemEntity entity, List<String> conflictWarnings) {
        return new KnowledgeItemResponse(
                entity.getId(),
                entity.getProjectId(),
                entity.getKind(),
                entity.getTitle(),
                entity.getContent(),
                entity.getStatus(),
                entity.getSourceType(),
                entity.getSourceReference(),
                entity.getContentHash(),
                entity.getApprovedBy(),
                entity.getApprovedAt(),
                entity.getReviewNotes(),
                entity.getSupersedesId(),
                entity.getVersion(),
                conflictWarnings,
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    public record CreateKnowledgeRequest(
            @NotBlank(message = "Knowledge kind is required")
            String kind,
            @NotBlank(message = "Title is required")
            @Size(max = 200)
            String title,
            @NotBlank(message = "Content is required")
            @Size(max = 20000)
            String content,
            String sourceType,
            @Size(max = 500)
            String sourceReference,
            Boolean autoApprove
    ) {}

    public record ReviewKnowledgeRequest(
            @NotBlank(message = "Action (APPROVE or REJECT) is required")
            String action,
            @Size(max = 200)
            String editedTitle,
            @Size(max = 20000)
            String editedContent,
            UUID supersedesItemId,
            @Size(max = 1000)
            String reviewNotes
    ) {}

    public record KnowledgeItemResponse(
            UUID id,
            UUID projectId,
            String kind,
            String title,
            String content,
            String status,
            String sourceType,
            String sourceReference,
            String contentHash,
            UUID approvedBy,
            Instant approvedAt,
            String reviewNotes,
            UUID supersedesId,
            long version,
            List<String> conflictWarnings,
            Instant createdAt,
            Instant updatedAt
    ) {}
}
