package com.forgeai.worker;

import com.forgeai.shared.domain.ChunkIndexRecordEntity;
import com.forgeai.shared.domain.GitHubRepositoryBindingEntity;
import com.forgeai.shared.domain.HandoffEntity;
import com.forgeai.shared.domain.JobEntity;
import com.forgeai.shared.domain.ProjectEntity;
import com.forgeai.shared.domain.SourceDocumentEntity;
import com.forgeai.shared.rag.RagContractDtos.IngestRequest;
import com.forgeai.shared.rag.RagContractDtos.IngestResponse;
import com.forgeai.shared.rag.RagContractDtos.IngestedChunkRecord;
import com.forgeai.shared.rag.RagServiceClient;
import com.forgeai.shared.repository.ChunkIndexRecordRepository;
import com.forgeai.shared.repository.GitHubRepositoryBindingRepository;
import com.forgeai.shared.repository.HandoffRepository;
import com.forgeai.shared.repository.JobRepository;
import com.forgeai.shared.repository.ProjectRepository;
import com.forgeai.shared.repository.SourceDocumentRepository;
import com.forgeai.shared.service.AuditService;
import com.forgeai.shared.service.DurableJobQueueService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Durable PostgreSQL-backed background worker processor.
 * Implements:
 * - Pessimistic lock claiming and leases (lockedBy, lockedUntil)
 * - Stale-job detection and recovery
 * - Generation/version guards so stale jobs cannot overwrite extended handoffs or re-index deleted sources
 * - Bounded retries with exponential backoff + jitter and dead-letter terminal states
 * - Graceful shutdown
 */
@Service
public class DurableJobWorkerService {

    private static final Logger log = LoggerFactory.getLogger(DurableJobWorkerService.class);

    private final JobRepository jobRepository;
    private final HandoffRepository handoffRepository;
    private final SourceDocumentRepository sourceRepository;
    private final ChunkIndexRecordRepository chunkRepository;
    private final ProjectRepository projectRepository;
    private final GitHubRepositoryBindingRepository githubBindingRepository;
    private final RagServiceClient ragServiceClient;
    private final DurableJobQueueService jobQueueService;
    private final AuditService auditService;
    private final TransactionTemplate transactionTemplate;

    private final String workerId;
    private final Duration leaseDuration;
    private final long baseBackoffMs;
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    public DurableJobWorkerService(
            JobRepository jobRepository,
            HandoffRepository handoffRepository,
            SourceDocumentRepository sourceRepository,
            ChunkIndexRecordRepository chunkRepository,
            ProjectRepository projectRepository,
            GitHubRepositoryBindingRepository githubBindingRepository,
            RagServiceClient ragServiceClient,
            DurableJobQueueService jobQueueService,
            AuditService auditService,
            TransactionTemplate transactionTemplate,
            @Value("${forgeai.worker.id:worker-1}") String workerId,
            @Value("${forgeai.worker.lease-seconds:60}") long leaseSeconds,
            @Value("${forgeai.worker.base-backoff-ms:1000}") long baseBackoffMs
    ) {
        this.jobRepository = jobRepository;
        this.handoffRepository = handoffRepository;
        this.sourceRepository = sourceRepository;
        this.chunkRepository = chunkRepository;
        this.projectRepository = projectRepository;
        this.githubBindingRepository = githubBindingRepository;
        this.ragServiceClient = ragServiceClient;
        this.jobQueueService = jobQueueService;
        this.auditService = auditService;
        this.transactionTemplate = transactionTemplate;
        this.workerId = workerId + "-" + UUID.randomUUID().toString().substring(0, 8);
        this.leaseDuration = Duration.ofSeconds(Math.max(5, leaseSeconds));
        this.baseBackoffMs = Math.max(50, baseBackoffMs);
    }

    @PreDestroy
    public void onShutdown() {
        log.info("Graceful shutdown initiated for worker {}", workerId);
        shuttingDown.set(true);
    }

    public boolean isShuttingDown() {
        return shuttingDown.get();
    }

    /**
     * Reclaims jobs stuck in RUNNING state whose lease has expired.
     */
    public int recoverStaleJobs() {
        if (shuttingDown.get()) {
            return 0;
        }
        Integer recovered = transactionTemplate.execute(status -> {
            Instant now = Instant.now();
            List<JobEntity> staleJobs = jobRepository.findStaleRunningJobs(now);
            int count = 0;
            for (JobEntity job : staleJobs) {
                if (job.getAttemptCount() >= job.getMaxAttempts()) {
                    job.setStatus("DEAD_LETTER");
                    job.setSafeErrorCode("LEASE_EXPIRED_MAX_ATTEMPTS");
                    job.setSafeErrorMessage("Worker lease expired and max attempts reached");
                } else {
                    job.setStatus("PENDING");
                    job.setRunAfter(now);
                    job.setSafeErrorCode("LEASE_RECOVERED");
                    job.setSafeErrorMessage("Recovered stale running job after lease expiration");
                }
                job.setLockedBy(null);
                job.setLockedUntil(null);
                jobRepository.save(job);
                count++;
            }
            return count;
        });
        return recovered != null ? recovered : 0;
    }

    /**
     * Claims and processes the next available job atomically.
     * Returns true if a job was claimed and processed.
     */
    public boolean claimAndProcessNextJob() {
        if (shuttingDown.get()) {
            return false;
        }

        JobEntity claimed = transactionTemplate.execute(status -> {
            Instant now = Instant.now();
            List<JobEntity> candidates = jobRepository.findClaimableJobs(now);
            if (candidates.isEmpty()) {
                return null;
            }
            JobEntity job = candidates.get(0);
            job.setStatus("RUNNING");
            job.setAttemptCount(job.getAttemptCount() + 1);
            job.setLockedBy(workerId);
            job.setLockedUntil(now.plus(leaseDuration));
            return jobRepository.save(job);
        });

        if (claimed == null) {
            return false;
        }

        String correlationId = "job-" + claimed.getId();
        MDC.put("requestId", correlationId);
        try {
            executeJob(claimed, correlationId);
            return true;
        } finally {
            MDC.remove("requestId");
        }
    }

    private void executeJob(JobEntity claimedJob, String correlationId) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                JobEntity job = jobRepository.findById(claimedJob.getId()).orElseThrow();
                switch (job.getJobType()) {
                    case "DAY_EIGHT_REMINDER" -> handleDayEightReminder(job);
                    case "DAY_TEN_EXPIRY" -> handleDayTenExpiry(job);
                    case "HANDOFF_CLEANUP" -> handleHandoffCleanup(job);
                    case "SOURCE_INGESTION" -> handleSourceIngestion(job, correlationId);
                    case "SOURCE_DELETION" -> handleSourceDeletion(job, correlationId);
                    case "PROJECT_CLEANUP" -> handleProjectCleanup(job, correlationId);
                    case "GITHUB_SYNC" -> handleGitHubSync(job);
                    case "QDRANT_RECONCILIATION", "CACHE_INVALIDATION" -> completeJob(job, "COMPLETED");
                    default -> throw new IllegalArgumentException("Unknown job type: " + job.getJobType());
                }
            });
        } catch (Exception e) {
            log.warn("Job {} (type={}, attempt={}/{}) failed: {}",
                    claimedJob.getId(), claimedJob.getJobType(), claimedJob.getAttemptCount(),
                    claimedJob.getMaxAttempts(), e.getMessage());
            recordJobFailure(claimedJob.getId(), e);
        }
    }

    private void handleDayEightReminder(JobEntity job) {
        Optional<HandoffEntity> handoffOpt = handoffRepository.findById(job.getEntityId());
        if (handoffOpt.isEmpty()) {
            completeJob(job, "SKIPPED_STALE");
            return;
        }
        HandoffEntity handoff = handoffOpt.get();
        // Generation/version guard + eligibility check: remind only if active, not deleted, not extended (FR-13)
        if (handoff.getDeletedAt() != null
                || !"ACTIVE".equals(handoff.getStatus())
                || handoff.getExtendedAt() != null
                || handoff.getVersion() != job.getEntityVersion()) {
            completeJob(job, "SKIPPED_STALE");
            return;
        }
        if (handoff.getReminderSentAt() == null) {
            handoff.setReminderSentAt(Instant.now());
            handoffRepository.save(handoff);
            auditService.record(handoff.getCreatedBy(), handoff.getProjectId(),
                    "HANDOFF_DAY8_REMINDER_SENT", "HANDOFF", handoff.getId(), null);
        }
        completeJob(job, "COMPLETED");
    }

    private void handleDayTenExpiry(JobEntity job) {
        Optional<HandoffEntity> handoffOpt = handoffRepository.findById(job.getEntityId());
        if (handoffOpt.isEmpty()) {
            completeJob(job, "SKIPPED_STALE");
            return;
        }
        HandoffEntity handoff = handoffOpt.get();
        // Generation/version guard: if user extended or deleted the handoff, this job is stale (FR-12)
        if (handoff.getDeletedAt() != null
                || !"ACTIVE".equals(handoff.getStatus())
                || handoff.getVersion() != job.getEntityVersion()
                || handoff.getExpiresAt().isAfter(Instant.now())) {
            completeJob(job, "SKIPPED_STALE");
            return;
        }
        handoff.setStatus("EXPIRED");
        handoff.setVersion(handoff.getVersion() + 1L);
        HandoffEntity saved = handoffRepository.save(handoff);

        jobQueueService.enqueueIdempotent(
                saved.getProjectId(),
                "HANDOFF_CLEANUP",
                "HANDOFF",
                saved.getId(),
                saved.getVersion(),
                "handoff-cleanup:" + saved.getId() + ":v" + saved.getVersion(),
                "{\"handoffId\":\"" + saved.getId() + "\"}",
                Instant.now()
        );
        auditService.record(saved.getCreatedBy(), saved.getProjectId(),
                "HANDOFF_EXPIRED", "HANDOFF", saved.getId(), null);
        completeJob(job, "COMPLETED");
    }

    private void handleHandoffCleanup(JobEntity job) {
        Optional<HandoffEntity> handoffOpt = handoffRepository.findById(job.getEntityId());
        if (handoffOpt.isPresent()) {
            HandoffEntity handoff = handoffOpt.get();
            if ("EXPIRED".equals(handoff.getStatus()) || "DELETED".equals(handoff.getStatus()) || handoff.getDeletedAt() != null) {
                handoff.setPromptBody("[CLEANED_UP]");
                handoff.setSourcesJson("[]");
                handoffRepository.save(handoff);
                auditService.record(handoff.getCreatedBy(), handoff.getProjectId(),
                        "HANDOFF_CLEANUP_VERIFIED", "HANDOFF", handoff.getId(), null);
            }
        }
        completeJob(job, "COMPLETED");
    }

    private void handleSourceIngestion(JobEntity job, String correlationId) {
        Optional<SourceDocumentEntity> sourceOpt = sourceRepository.findById(job.getEntityId());
        if (sourceOpt.isEmpty()) {
            completeJob(job, "SKIPPED_STALE");
            return;
        }
        SourceDocumentEntity source = sourceOpt.get();
        // Critical deletion/version guard: deleted entities or newer source generations cannot be indexed
        if (source.getDeletedAt() != null
                || "DELETING".equals(source.getIngestionStatus())
                || "DELETED".equals(source.getIngestionStatus())
                || source.getSourceVersion() != job.getEntityVersion()) {
            completeJob(job, "SKIPPED_STALE");
            return;
        }

        Optional<ProjectEntity> projectOpt = projectRepository.findById(source.getProjectId());
        if (projectOpt.isEmpty() || projectOpt.get().getDeletedAt() != null) {
            completeJob(job, "SKIPPED_STALE");
            return;
        }

        IngestRequest req = new IngestRequest(
                correlationId,
                source.getProjectId(),
                source.getId(),
                source.getSourceType(),
                source.getSourceReference(),
                source.getSourceVersion(),
                source.getContentHash(),
                source.getContent()
        );

        IngestResponse resp = ragServiceClient.ingest(req);

        // Re-verify authoritative state before writing index records (race protection against concurrent DELETE)
        SourceDocumentEntity refreshed = sourceRepository.findById(source.getId()).orElseThrow();
        if (refreshed.getDeletedAt() != null || refreshed.getSourceVersion() != job.getEntityVersion()) {
            ragServiceClient.deleteSource(source.getId(), correlationId);
            completeJob(job, "SKIPPED_STALE");
            return;
        }

        chunkRepository.deleteBySourceId(refreshed.getId());
        if (resp.chunks() != null) {
            for (IngestedChunkRecord chunk : resp.chunks()) {
                ChunkIndexRecordEntity record = new ChunkIndexRecordEntity();
                record.setProjectId(refreshed.getProjectId());
                record.setSourceId(refreshed.getId());
                record.setChunkHash(chunk.chunkHash());
                record.setEmbeddingModel(chunk.embeddingModel());
                record.setEmbeddingRevision(chunk.embeddingRevision());
                record.setVectorId(chunk.vectorId());
                record.setIndexStatus("INDEXED");
                record.setSourceVersion(refreshed.getSourceVersion());
                chunkRepository.save(record);
            }
        }

        refreshed.setIngestionStatus("INDEXED");
        refreshed.setLastIndexedAt(Instant.now());
        sourceRepository.save(refreshed);
        completeJob(job, "COMPLETED");
    }

    private void handleSourceDeletion(JobEntity job, String correlationId) {
        UUID sourceId = job.getEntityId();
        ragServiceClient.deleteSource(sourceId, correlationId);
        chunkRepository.deleteBySourceId(sourceId);

        sourceRepository.findById(sourceId).ifPresent(source -> {
            source.setIngestionStatus("DELETED");
            source.setContent("[DELETED]");
            if (source.getDeletedAt() == null) {
                source.setDeletedAt(Instant.now());
            }
            sourceRepository.save(source);
        });
        completeJob(job, "COMPLETED");
    }

    private void handleProjectCleanup(JobEntity job, String correlationId) {
        UUID projectId = job.getEntityId();
        List<SourceDocumentEntity> sources = sourceRepository.findByProjectId(projectId);
        for (SourceDocumentEntity source : sources) {
            ragServiceClient.deleteSource(source.getId(), correlationId);
            source.setDeletedAt(Instant.now());
            source.setIngestionStatus("DELETED");
            source.setContent("[DELETED]");
            source.setSourceVersion(source.getSourceVersion() + 1L);
            sourceRepository.save(source);
        }
        chunkRepository.deleteByProjectId(projectId);

        List<HandoffEntity> handoffs = handoffRepository.findByProjectIdAndDeletedAtIsNullOrderByCreatedAtDesc(projectId);
        for (HandoffEntity handoff : handoffs) {
            handoff.setDeletedAt(Instant.now());
            handoff.setStatus("DELETED");
            handoff.setPromptBody("[CLEANED_UP]");
            handoff.setVersion(handoff.getVersion() + 1L);
            handoffRepository.save(handoff);
        }

        auditService.record(null, projectId, "PROJECT_CLEANUP_VERIFIED", "PROJECT", projectId, null);
        completeJob(job, "COMPLETED");
    }

    private void handleGitHubSync(JobEntity job) {
        Optional<GitHubRepositoryBindingEntity> bindingOpt = githubBindingRepository.findById(job.getEntityId());
        if (bindingOpt.isEmpty()) {
            completeJob(job, "SKIPPED_STALE");
            return;
        }
        GitHubRepositoryBindingEntity binding = bindingOpt.get();
        // Respect access revocation and read-only scope (FR-10, FR-11)
        if (!"ACTIVE".equals(binding.getStatus())
                || binding.getRevokedAt() != null
                || binding.getVersion() != job.getEntityVersion()) {
            completeJob(job, "SKIPPED_STALE");
            return;
        }
        binding.setLastSyncedAt(Instant.now());
        githubBindingRepository.save(binding);
        completeJob(job, "COMPLETED");
    }

    private void completeJob(JobEntity job, String finalStatus) {
        job.setStatus(finalStatus);
        job.setLockedBy(null);
        job.setLockedUntil(null);
        job.setCompletedAt(Instant.now());
        job.setSafeErrorCode(null);
        job.setSafeErrorMessage(null);
        jobRepository.save(job);
    }

    private void recordJobFailure(UUID jobId, Exception error) {
        transactionTemplate.executeWithoutResult(status -> {
            JobEntity job = jobRepository.findById(jobId).orElse(null);
            if (job == null) {
                return;
            }
            job.setLockedBy(null);
            job.setLockedUntil(null);
            job.setSafeErrorCode(error.getClass().getSimpleName());
            String msg = error.getMessage() != null ? error.getMessage() : "Job execution error";
            job.setSafeErrorMessage(msg.length() > 480 ? msg.substring(0, 480) : msg);

            if (job.getAttemptCount() >= job.getMaxAttempts()) {
                job.setStatus("DEAD_LETTER");
            } else {
                job.setStatus("PENDING");
                long expMs = baseBackoffMs * (1L << Math.min(6, job.getAttemptCount() - 1));
                long jitterMs = ThreadLocalRandom.current().nextLong(0, Math.max(25, baseBackoffMs / 2));
                job.setRunAfter(Instant.now().plusMillis(expMs + jitterMs));
            }
            jobRepository.save(job);
        });
    }
}
