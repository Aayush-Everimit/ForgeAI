package com.forgeai.api.github;

import com.forgeai.api.common.ApiException;
import com.forgeai.api.security.ProjectAccessGuard;
import com.forgeai.shared.domain.GitHubRepositoryBindingEntity;
import com.forgeai.shared.repository.GitHubRepositoryBindingRepository;
import com.forgeai.shared.service.AuditService;
import com.forgeai.shared.service.DurableJobQueueService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
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
@RequestMapping("/api/v1/projects/{projectId}/github/repositories")
public class GitHubIntegrationController {

    private final GitHubRepositoryBindingRepository bindingRepository;
    private final ProjectAccessGuard projectAccessGuard;
    private final DurableJobQueueService jobQueueService;
    private final AuditService auditService;

    public GitHubIntegrationController(
            GitHubRepositoryBindingRepository bindingRepository,
            ProjectAccessGuard projectAccessGuard,
            DurableJobQueueService jobQueueService,
            AuditService auditService
    ) {
        this.bindingRepository = bindingRepository;
        this.projectAccessGuard = projectAccessGuard;
        this.jobQueueService = jobQueueService;
        this.auditService = auditService;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<GitHubRepositoryBindingResponse> listRepositories(@PathVariable UUID projectId) {
        projectAccessGuard.requireOwnedProject(projectId);
        return bindingRepository.findByProjectIdOrderByCreatedAtDesc(projectId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @PostMapping
    @Transactional
    public ResponseEntity<GitHubRepositoryBindingResponse> bindRepository(
            @PathVariable UUID projectId,
            @Valid @RequestBody BindRepositoryRequest request
    ) {
        ProjectAccessGuard.AuthorizedProjectContext ctx = projectAccessGuard.requireOwnedProject(projectId);

        GitHubRepositoryBindingEntity binding = new GitHubRepositoryBindingEntity();
        binding.setProjectId(projectId);
        binding.setInstallationId(request.installationId().trim());
        binding.setRepositoryFullName(request.repositoryFullName().trim());
        binding.setDefaultBranch(request.defaultBranch() != null && !request.defaultBranch().isBlank()
                ? request.defaultBranch().trim()
                : "main");
        // Release 0 strictly enforces READ_ONLY repository permissions (FR-10, FR-11)
        binding.setAccessMode("READ_ONLY");
        binding.setStatus("ACTIVE");
        binding.setVersion(1L);
        GitHubRepositoryBindingEntity saved = bindingRepository.save(binding);

        if (request.triggerSync() == null || request.triggerSync()) {
            jobQueueService.enqueueIdempotent(
                    projectId,
                    "GITHUB_SYNC",
                    "GITHUB_BINDING",
                    saved.getId(),
                    saved.getVersion(),
                    "github-sync:" + saved.getId() + ":v" + saved.getVersion(),
                    "{\"bindingId\":\"" + saved.getId() + "\",\"repo\":\"" + saved.getRepositoryFullName() + "\"}",
                    Instant.now()
            );
        }

        auditService.record(ctx.user().getId(), projectId, "GITHUB_REPO_BOUND", "GITHUB_BINDING", saved.getId(), null);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(saved));
    }

    @PostMapping("/{bindingId}/revoke")
    @Transactional
    public GitHubRepositoryBindingResponse revokeRepository(
            @PathVariable UUID projectId,
            @PathVariable UUID bindingId
    ) {
        ProjectAccessGuard.AuthorizedProjectContext ctx = projectAccessGuard.requireOwnedProject(projectId);
        GitHubRepositoryBindingEntity binding = bindingRepository.findByIdAndProjectId(bindingId, projectId)
                .orElseThrow(() -> ApiException.notFound("GITHUB_BINDING_NOT_FOUND", "GitHub repository binding not found"));

        binding.setStatus("REVOKED");
        binding.setRevokedAt(Instant.now());
        // Increment version so any queued GITHUB_SYNC job is rejected by the worker generation guard
        binding.setVersion(binding.getVersion() + 1L);
        GitHubRepositoryBindingEntity saved = bindingRepository.save(binding);

        auditService.record(ctx.user().getId(), projectId, "GITHUB_REPO_REVOKED", "GITHUB_BINDING", saved.getId(), null);
        return toResponse(saved);
    }

    private GitHubRepositoryBindingResponse toResponse(GitHubRepositoryBindingEntity entity) {
        return new GitHubRepositoryBindingResponse(
                entity.getId(),
                entity.getProjectId(),
                entity.getInstallationId(),
                entity.getRepositoryFullName(),
                entity.getDefaultBranch(),
                entity.getAccessMode(),
                entity.getStatus(),
                entity.getLastSyncedAt(),
                entity.getRevokedAt(),
                entity.getVersion(),
                entity.getCreatedAt()
        );
    }

    public record BindRepositoryRequest(
            @NotBlank(message = "GitHub App installationId is required")
            @Size(max = 120)
            String installationId,
            @NotBlank(message = "Repository full name (owner/repo) is required")
            @Pattern(regexp = "^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$", message = "Must be in 'owner/repo' format")
            String repositoryFullName,
            @Size(max = 120)
            String defaultBranch,
            Boolean triggerSync
    ) {}

    public record GitHubRepositoryBindingResponse(
            UUID id,
            UUID projectId,
            String installationId,
            String repositoryFullName,
            String defaultBranch,
            String accessMode,
            String status,
            Instant lastSyncedAt,
            Instant revokedAt,
            long version,
            Instant createdAt
    ) {}
}
