package com.forgeai.shared.service;

import com.forgeai.shared.domain.JobEntity;
import com.forgeai.shared.repository.JobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared service for creating and replaying durable PostgreSQL-backed jobs.
 * Enforces idempotency via unique idempotency keys and records entity version/generation guards.
 */
@Service
public class DurableJobQueueService {

    private final JobRepository jobRepository;

    public DurableJobQueueService(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    @Transactional
    public JobEntity enqueueIdempotent(
            UUID projectId,
            String jobType,
            String entityType,
            UUID entityId,
            long entityVersion,
            String idempotencyKey,
            String payloadJson,
            Instant runAfter
    ) {
        Optional<JobEntity> existing = jobRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        JobEntity job = new JobEntity();
        job.setProjectId(projectId);
        job.setJobType(jobType);
        job.setEntityType(entityType);
        job.setEntityId(entityId);
        job.setEntityVersion(entityVersion);
        job.setIdempotencyKey(idempotencyKey);
        job.setPayloadJson(payloadJson);
        job.setStatus("PENDING");
        job.setAttemptCount(0);
        job.setMaxAttempts(5);
        job.setRunAfter(runAfter != null ? runAfter : Instant.now());
        return jobRepository.save(job);
    }
}
