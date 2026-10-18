package com.forgeai.api.jobs;

import com.forgeai.api.common.ApiException;
import com.forgeai.api.security.ProjectAccessGuard;
import com.forgeai.shared.domain.JobEntity;
import com.forgeai.shared.repository.JobRepository;
import com.forgeai.shared.service.AuditService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class JobController {

    private final JobRepository jobRepository;
    private final ProjectAccessGuard projectAccessGuard;
    private final AuditService auditService;

    public JobController(
            JobRepository jobRepository,
            ProjectAccessGuard projectAccessGuard,
            AuditService auditService
    ) {
        this.jobRepository = jobRepository;
        this.projectAccessGuard = projectAccessGuard;
        this.auditService = auditService;
    }

    @GetMapping("/projects/{projectId}/jobs")
    @Transactional(readOnly = true)
    public List<JobResponse> listProjectJobs(@PathVariable UUID projectId) {
        projectAccessGuard.requireOwnedProject(projectId);
        return jobRepository.findByProjectIdOrderByCreatedAtDesc(projectId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @GetMapping("/jobs/{jobId}")
    @Transactional(readOnly = true)
    public JobResponse getJob(@PathVariable UUID jobId) {
        JobEntity job = jobRepository.findById(jobId)
                .orElseThrow(() -> ApiException.notFound("JOB_NOT_FOUND", "Job not found"));
        if (job.getProjectId() != null) {
            projectAccessGuard.requireOwnedProject(job.getProjectId());
        }
        return toResponse(job);
    }

    @PostMapping("/jobs/{jobId}/replay")
    @Transactional
    public JobResponse replayJob(@PathVariable UUID jobId) {
        JobEntity job = jobRepository.findById(jobId)
                .orElseThrow(() -> ApiException.notFound("JOB_NOT_FOUND", "Job not found"));
        ProjectAccessGuard.AuthorizedProjectContext ctx = null;
        if (job.getProjectId() != null) {
            ctx = projectAccessGuard.requireOwnedProject(job.getProjectId());
        }
        if (!"FAILED".equals(job.getStatus()) && !"DEAD_LETTER".equals(job.getStatus())) {
            throw ApiException.conflict("JOB_NOT_REPLAYABLE", "Only FAILED or DEAD_LETTER jobs can be replayed");
        }
        job.setStatus("PENDING");
        job.setAttemptCount(0);
        job.setRunAfter(Instant.now());
        job.setLockedBy(null);
        job.setLockedUntil(null);
        job.setSafeErrorCode(null);
        job.setSafeErrorMessage(null);
        JobEntity saved = jobRepository.save(job);

        if (ctx != null) {
            auditService.record(ctx.user().getId(), job.getProjectId(), "JOB_REPLAYED", "JOB", saved.getId(), null);
        }
        return toResponse(saved);
    }

    private JobResponse toResponse(JobEntity entity) {
        return new JobResponse(
                entity.getId(),
                entity.getProjectId(),
                entity.getJobType(),
                entity.getEntityType(),
                entity.getEntityId(),
                entity.getEntityVersion(),
                entity.getStatus(),
                entity.getAttemptCount(),
                entity.getMaxAttempts(),
                entity.getRunAfter(),
                entity.getSafeErrorCode(),
                entity.getSafeErrorMessage(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getCompletedAt()
        );
    }

    public record JobResponse(
            UUID id,
            UUID projectId,
            String jobType,
            String entityType,
            UUID entityId,
            long entityVersion,
            String status,
            int attemptCount,
            int maxAttempts,
            Instant runAfter,
            String safeErrorCode,
            String safeErrorMessage,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt
    ) {}
}
