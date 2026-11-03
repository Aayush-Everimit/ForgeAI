package com.forgeai.api.projects;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.api.security.CurrentUserService;
import com.forgeai.api.security.ProjectAccessGuard;
import com.forgeai.shared.domain.JobEntity;
import com.forgeai.shared.domain.ProjectEntity;
import com.forgeai.shared.domain.UserEntity;
import com.forgeai.shared.repository.ProjectRepository;
import com.forgeai.shared.service.AuditService;
import com.forgeai.shared.service.DurableJobQueueService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectRepository projectRepository;
    private final CurrentUserService currentUserService;
    private final ProjectAccessGuard projectAccessGuard;
    private final DurableJobQueueService jobQueueService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public ProjectController(
            ProjectRepository projectRepository,
            CurrentUserService currentUserService,
            ProjectAccessGuard projectAccessGuard,
            DurableJobQueueService jobQueueService,
            AuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.projectRepository = projectRepository;
        this.currentUserService = currentUserService;
        this.projectAccessGuard = projectAccessGuard;
        this.jobQueueService = jobQueueService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<ProjectResponse> listProjects() {
        UserEntity user = currentUserService.requireCurrentUser();
        return projectRepository.findByOwnerUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(user.getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @PostMapping
    @Transactional
    public ResponseEntity<ProjectResponse> createProject(@Valid @RequestBody CreateProjectRequest request) {
        UserEntity user = currentUserService.requireCurrentUser();
        ProjectEntity project = new ProjectEntity();
        project.setOwnerUserId(user.getId());
        project.setName(request.name().trim());
        project.setDescription(request.description());
        project.setStackMetadataJson(serializeMetadata(request.stackMetadata()));
        project.setVersion(1L);
        ProjectEntity saved = projectRepository.save(project);

        auditService.record(user.getId(), saved.getId(), "PROJECT_CREATED", "PROJECT", saved.getId(), null);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(saved));
    }

    @GetMapping("/{projectId}")
    @Transactional(readOnly = true)
    public ProjectResponse getProject(@PathVariable UUID projectId) {
        ProjectAccessGuard.AuthorizedProjectContext ctx = projectAccessGuard.requireOwnedProject(projectId);
        return toResponse(ctx.project());
    }

    @PatchMapping("/{projectId}")
    @Transactional
    public ProjectResponse updateProject(
            @PathVariable UUID projectId,
            @Valid @RequestBody UpdateProjectRequest request
    ) {
        ProjectAccessGuard.AuthorizedProjectContext ctx = projectAccessGuard.requireOwnedProject(projectId);
        ProjectEntity project = ctx.project();
        if (request.name() != null && !request.name().isBlank()) {
            project.setName(request.name().trim());
        }
        if (request.description() != null) {
            project.setDescription(request.description());
        }
        if (request.stackMetadata() != null) {
            project.setStackMetadataJson(serializeMetadata(request.stackMetadata()));
        }
        project.setVersion(project.getVersion() + 1L);
        ProjectEntity saved = projectRepository.save(project);

        auditService.record(ctx.user().getId(), saved.getId(), "PROJECT_UPDATED", "PROJECT", saved.getId(), null);
        return toResponse(saved);
    }

    @DeleteMapping("/{projectId}")
    @Transactional
    public ResponseEntity<DeleteProjectResponse> deleteProject(@PathVariable UUID projectId) {
        UserEntity user = currentUserService.requireCurrentUser();
        Optional<ProjectEntity> existingOpt = projectRepository.findById(projectId);
        if (existingOpt.isEmpty() || !existingOpt.get().getOwnerUserId().equals(user.getId())) {
            throw com.forgeai.api.common.ApiException.notFound("PROJECT_NOT_FOUND", "Project not found or not accessible");
        }

        ProjectEntity project = existingOpt.get();
        if (project.getDeletedAt() == null) {
            project.setDeletedAt(Instant.now());
            project.setVersion(project.getVersion() + 1L);
            projectRepository.save(project);
        }

        JobEntity cleanupJob = jobQueueService.enqueueIdempotent(
                project.getId(),
                "PROJECT_CLEANUP",
                "PROJECT",
                project.getId(),
                project.getVersion(),
                "project-cleanup:" + project.getId() + ":v" + project.getVersion(),
                "{\"projectId\":\"" + project.getId() + "\"}",
                Instant.now()
        );

        auditService.record(user.getId(), project.getId(), "PROJECT_DELETED", "PROJECT", project.getId(), null);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new DeleteProjectResponse("DELETING", project.getId(), cleanupJob.getId()));
    }

    private String serializeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Object> deserializeMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private ProjectResponse toResponse(ProjectEntity entity) {
        return new ProjectResponse(
                entity.getId(),
                entity.getOwnerUserId(),
                entity.getName(),
                entity.getDescription(),
                deserializeMetadata(entity.getStackMetadataJson()),
                entity.getVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    public record CreateProjectRequest(
            @NotBlank(message = "Project name must not be blank")
            @Size(max = 120, message = "Project name must be at most 120 characters")
            String name,
            @Size(max = 2000, message = "Description must be at most 2000 characters")
            String description,
            Map<String, Object> stackMetadata
    ) {}

    public record UpdateProjectRequest(
            @Size(max = 120, message = "Project name must be at most 120 characters")
            String name,
            @Size(max = 2000, message = "Description must be at most 2000 characters")
            String description,
            Map<String, Object> stackMetadata
    ) {}

    public record ProjectResponse(
            UUID id,
            UUID ownerUserId,
            String name,
            String description,
            Map<String, Object> stackMetadata,
            long version,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record DeleteProjectResponse(
            String status,
            UUID projectId,
            UUID cleanupJobId
    ) {}
}
