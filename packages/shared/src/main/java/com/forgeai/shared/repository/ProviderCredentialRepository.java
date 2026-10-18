package com.forgeai.shared.repository;

import com.forgeai.shared.domain.ProviderCredentialEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProviderCredentialRepository extends JpaRepository<ProviderCredentialEntity, UUID> {
    List<ProviderCredentialEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);
    Optional<ProviderCredentialEntity> findByIdAndUserId(UUID id, UUID userId);
    List<ProviderCredentialEntity> findByUserIdAndProviderAndStatus(UUID userId, String provider, String status);
}
