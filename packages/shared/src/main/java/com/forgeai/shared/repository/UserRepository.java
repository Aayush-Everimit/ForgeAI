package com.forgeai.shared.repository;

import com.forgeai.shared.domain.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {
    Optional<UserEntity> findByGithubIdAndDeletedAtIsNull(String githubId);
    Optional<UserEntity> findByIdAndDeletedAtIsNull(UUID id);
}
