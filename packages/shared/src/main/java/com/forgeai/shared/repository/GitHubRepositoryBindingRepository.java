package com.forgeai.shared.repository;

import com.forgeai.shared.domain.GitHubRepositoryBindingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GitHubRepositoryBindingRepository extends JpaRepository<GitHubRepositoryBindingEntity, UUID> {
    List<GitHubRepositoryBindingEntity> findByProjectIdOrderByCreatedAtDesc(UUID projectId);
    Optional<GitHubRepositoryBindingEntity> findByIdAndProjectId(UUID id, UUID projectId);
}
