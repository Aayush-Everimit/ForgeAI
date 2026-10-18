package com.forgeai.worker;

import com.forgeai.shared.domain.HandoffEntity;
import com.forgeai.shared.domain.JobEntity;
import com.forgeai.shared.domain.ProjectEntity;
import com.forgeai.shared.domain.SourceDocumentEntity;
import com.forgeai.shared.domain.UserEntity;
import com.forgeai.shared.repository.ChunkIndexRecordRepository;
import com.forgeai.shared.repository.HandoffRepository;
import com.forgeai.shared.repository.JobRepository;
import com.forgeai.shared.repository.ProjectRepository;
import com.forgeai.shared.repository.SourceDocumentRepository;
import com.forgeai.shared.repository.UserRepository;
import com.forgeai.shared.service.DurableJobQueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class DurableJobWorkerIntegrationTest {

    @Autowired
    private DurableJobWorkerService workerService;

    @Autowired
    private DurableJobQueueService jobQueueService;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private HandoffRepository handoffRepository;

    @Autowired
    private SourceDocumentRepository sourceRepository;

    @Autowired
    private ChunkIndexRecordRepository chunkRepository;

    private UserEntity user;
    private ProjectEntity project;

    @BeforeEach
    void setUp() {
        jobRepository.deleteAll();
        chunkRepository.deleteAll();
        sourceRepository.deleteAll();
        handoffRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.deleteAll();

        user = new UserEntity();
        user.setGithubId("worker-user-1");
        user.setGithubLogin("worker-user");
        user.setDisplayName("Worker Test User");
        user = userRepository.save(user);

        project = new ProjectEntity();
        project.setOwnerUserId(user.getId());
        project.setName("Worker Test Project");
        project = projectRepository.save(project);
    }

    @Test
    void preventsDeletedOrNewerVersionSourceFromBeingReIndexedByStaleJob() {
        SourceDocumentEntity source = new SourceDocumentEntity();
        source.setProjectId(project.getId());
        source.setSourceType("DOCUMENT");
        source.setSourceReference("docs/secret.md");
        source.setContent("Sensitive content");
        source.setContentHash("sha256:123");
        source.setSourceVersion(1L);
        source.setIngestionStatus("PENDING");
        source = sourceRepository.save(source);

        // Enqueue ingestion job at version 1
        JobEntity ingestJob = jobQueueService.enqueueIdempotent(
                project.getId(),
                "SOURCE_INGESTION",
                "SOURCE",
                source.getId(),
                1L,
                "ingest-stale-" + source.getId(),
                "{}",
                Instant.now().minusSeconds(5)
        );

        // User deletes the source before worker processes the ingestion job (increments version to 2)
        source.setDeletedAt(Instant.now());
        source.setIngestionStatus("DELETING");
        source.setSourceVersion(2L);
        sourceRepository.save(source);

        boolean processed = workerService.claimAndProcessNextJob();
        assertThat(processed).isTrue();

        JobEntity updatedJob = jobRepository.findById(ingestJob.getId()).orElseThrow();
        assertThat(updatedJob.getStatus()).isEqualTo("SKIPPED_STALE");
        assertThat(chunkRepository.findBySourceId(source.getId())).isEmpty();
    }

    @Test
    void handoffExtensionGenerationGuardPreventsStaleDayTenExpiryFromExpiringExtendedHandoff() {
        HandoffEntity handoff = new HandoffEntity();
        handoff.setProjectId(project.getId());
        handoff.setCreatedBy(user.getId());
        handoff.setTask("Test expiry guard");
        handoff.setTaskType("feature");
        handoff.setPromptBody("Prompt body");
        handoff.setStatus("ACTIVE");
        handoff.setExpiresAt(Instant.now().plus(10, ChronoUnit.DAYS));
        handoff.setExtendedAt(Instant.now());
        handoff.setVersion(2L); // Extended to version 2
        handoff = handoffRepository.save(handoff);

        // Stale v1 expiry job becomes runnable now
        JobEntity staleExpiryJob = jobQueueService.enqueueIdempotent(
                project.getId(),
                "DAY_TEN_EXPIRY",
                "HANDOFF",
                handoff.getId(),
                1L,
                "stale-expiry-" + handoff.getId(),
                "{}",
                Instant.now().minusSeconds(10)
        );

        assertThat(workerService.claimAndProcessNextJob()).isTrue();

        JobEntity afterJob = jobRepository.findById(staleExpiryJob.getId()).orElseThrow();
        assertThat(afterJob.getStatus()).isEqualTo("SKIPPED_STALE");

        HandoffEntity afterHandoff = handoffRepository.findById(handoff.getId()).orElseThrow();
        assertThat(afterHandoff.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void retriesFailingJobWithBackoffAndTransitionsToDeadLetterAtMaxAttempts() {
        SourceDocumentEntity source = new SourceDocumentEntity();
        source.setProjectId(project.getId());
        source.setSourceType("DOCUMENT");
        source.setSourceReference("docs/retry.md");
        source.setContent("Will fail because RAG port 19998 is unreachable in test");
        source.setContentHash("sha256:retry");
        source.setSourceVersion(1L);
        source.setIngestionStatus("PENDING");
        source = sourceRepository.save(source);

        JobEntity job = jobQueueService.enqueueIdempotent(
                project.getId(),
                "SOURCE_INGESTION",
                "SOURCE",
                source.getId(),
                1L,
                "ingest-retry-" + source.getId(),
                "{}",
                Instant.now().minusSeconds(5)
        );
        job.setMaxAttempts(2);
        jobRepository.save(job);

        // Attempt 1 fails -> status returns to PENDING with future runAfter
        assertThat(workerService.claimAndProcessNextJob()).isTrue();
        JobEntity afterAttempt1 = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(afterAttempt1.getAttemptCount()).isEqualTo(1);
        assertThat(afterAttempt1.getStatus()).isEqualTo("PENDING");
        assertThat(afterAttempt1.getSafeErrorCode()).isNotBlank();

        // Force runAfter to now for Attempt 2 -> hits maxAttempts=2 -> transitions to DEAD_LETTER
        afterAttempt1.setRunAfter(Instant.now().minusSeconds(1));
        jobRepository.save(afterAttempt1);

        assertThat(workerService.claimAndProcessNextJob()).isTrue();
        JobEntity afterAttempt2 = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(afterAttempt2.getAttemptCount()).isEqualTo(2);
        assertThat(afterAttempt2.getStatus()).isEqualTo("DEAD_LETTER");
    }

    @Test
    void recoversStaleRunningJobsWhenLeaseHasExpired() {
        JobEntity stuckJob = new JobEntity();
        stuckJob.setProjectId(project.getId());
        stuckJob.setJobType("CACHE_INVALIDATION");
        stuckJob.setEntityType("PROJECT");
        stuckJob.setEntityId(project.getId());
        stuckJob.setEntityVersion(1L);
        stuckJob.setIdempotencyKey("stuck-lease-" + UUID.randomUUID());
        stuckJob.setStatus("RUNNING");
        stuckJob.setAttemptCount(1);
        stuckJob.setMaxAttempts(5);
        stuckJob.setRunAfter(Instant.now().minusSeconds(120));
        stuckJob.setLockedBy("crashed-worker");
        stuckJob.setLockedUntil(Instant.now().minusSeconds(30)); // expired lease
        stuckJob = jobRepository.save(stuckJob);

        int recovered = workerService.recoverStaleJobs();
        assertThat(recovered).isEqualTo(1);

        JobEntity reclaimed = jobRepository.findById(stuckJob.getId()).orElseThrow();
        assertThat(reclaimed.getStatus()).isEqualTo("PENDING");
        assertThat(reclaimed.getLockedBy()).isNull();

        assertThat(workerService.claimAndProcessNextJob()).isTrue();
        JobEntity completed = jobRepository.findById(stuckJob.getId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo("COMPLETED");
    }
}
