package com.forgeai.shared.repository;

import com.forgeai.shared.domain.JobEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobRepository extends JpaRepository<JobEntity, UUID> {
    Optional<JobEntity> findByIdempotencyKey(String idempotencyKey);
    List<JobEntity> findByProjectIdOrderByCreatedAtDesc(UUID projectId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT j FROM JobEntity j WHERE j.status = 'PENDING' AND j.runAfter <= :now ORDER BY j.runAfter ASC")
    List<JobEntity> findClaimableJobs(@Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT j FROM JobEntity j WHERE j.status = 'RUNNING' AND j.lockedUntil < :now")
    List<JobEntity> findStaleRunningJobs(@Param("now") Instant now);
}
