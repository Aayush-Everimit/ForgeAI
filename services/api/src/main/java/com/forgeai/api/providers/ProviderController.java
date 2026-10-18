package com.forgeai.api.providers;

import com.forgeai.api.common.ApiException;
import com.forgeai.api.security.CurrentUserService;
import com.forgeai.shared.crypto.EnvelopeEncryptionService;
import com.forgeai.shared.domain.ProviderCredentialEntity;
import com.forgeai.shared.domain.UserEntity;
import com.forgeai.shared.repository.ProviderCredentialRepository;
import com.forgeai.shared.service.AuditService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/providers")
public class ProviderController {

    private static final List<ModelDescriptorResponse> CATALOGUE = List.of(
            new ModelDescriptorResponse(
                    "anthropic/claude-3.7-sonnet",
                    "Claude 3.7 Sonnet (OpenRouter)",
                    "OPENROUTER",
                    200000,
                    true,
                    true,
                    true,
                    "openrouter/auto"
            ),
            new ModelDescriptorResponse(
                    "openai/gpt-4.1",
                    "GPT-4.1 (OpenRouter)",
                    "OPENROUTER",
                    128000,
                    true,
                    true,
                    true,
                    "anthropic/claude-3.7-sonnet"
            ),
            new ModelDescriptorResponse(
                    "deepseek/deepseek-chat-v3",
                    "DeepSeek V3 (OpenRouter)",
                    "OPENROUTER",
                    64000,
                    false,
                    true,
                    true,
                    "anthropic/claude-3.7-sonnet"
            ),
            new ModelDescriptorResponse(
                    "google/gemini-2.5-pro",
                    "Gemini 2.5 Pro (OpenRouter)",
                    "OPENROUTER",
                    1000000,
                    true,
                    true,
                    true,
                    "anthropic/claude-3.7-sonnet"
            )
    );

    private final ProviderCredentialRepository credentialRepository;
    private final EnvelopeEncryptionService encryptionService;
    private final CurrentUserService currentUserService;
    private final AuditService auditService;

    public ProviderController(
            ProviderCredentialRepository credentialRepository,
            EnvelopeEncryptionService encryptionService,
            CurrentUserService currentUserService,
            AuditService auditService
    ) {
        this.credentialRepository = credentialRepository;
        this.encryptionService = encryptionService;
        this.currentUserService = currentUserService;
        this.auditService = auditService;
    }

    @GetMapping("/models")
    public List<ModelDescriptorResponse> listModels() {
        currentUserService.requireCurrentUser();
        return CATALOGUE;
    }

    @PostMapping("/route-preview")
    @Transactional(readOnly = true)
    public RoutePreviewResponse routePreview(@Valid @RequestBody RoutePreviewRequest request) {
        UserEntity user = currentUserService.requireCurrentUser();
        List<String> warnings = new ArrayList<>();

        boolean hasActiveKey = !credentialRepository
                .findByUserIdAndProviderAndStatus(user.getId(), "OPENROUTER", "ACTIVE")
                .isEmpty();
        if (!hasActiveKey) {
            warnings.add("BYOK_REQUIRED: No active OpenRouter API key is stored for your account. Model inference requires a user-supplied key; ForgeAI never uses operator-funded keys.");
        }

        Optional<ModelDescriptorResponse> requestedOpt = CATALOGUE.stream()
                .filter(m -> m.id().equalsIgnoreCase(request.requestedModel().trim()))
                .findFirst();

        boolean requireTools = Boolean.TRUE.equals(request.requireTools());
        boolean requireJson = Boolean.TRUE.equals(request.requireJson());
        boolean allowFallback = request.allowFallback() == null || request.allowFallback();

        if (requestedOpt.isEmpty()) {
            if (!allowFallback) {
                throw ApiException.badRequest("MODEL_UNAVAILABLE", "Requested model is not in catalogue and fallback is disabled");
            }
            ModelDescriptorResponse fallback = CATALOGUE.get(0);
            warnings.add("FALLBACK_DISCLOSED: Requested model '" + request.requestedModel()
                    + "' is unavailable; falling back to '" + fallback.id() + "'.");
            return new RoutePreviewResponse(fallback.id(), true, "MODEL_NOT_IN_CATALOGUE", warnings);
        }

        ModelDescriptorResponse model = requestedOpt.get();
        if ((requireTools && !model.supportsTools()) || (requireJson && !model.supportsJson())) {
            if (!allowFallback) {
                throw ApiException.badRequest("MODEL_CAPABILITY_MISMATCH",
                        "Model '" + model.id() + "' does not satisfy capability requirements and fallback is disabled");
            }
            String fallbackId = model.recommendedFallbackModel();
            warnings.add("FALLBACK_DISCLOSED: Model '" + model.id()
                    + "' lacks required capabilities (tools=" + requireTools + ", json=" + requireJson
                    + "); visibly falling back to '" + fallbackId + "'.");
            return new RoutePreviewResponse(fallbackId, true, "CAPABILITY_MISMATCH", warnings);
        }

        return new RoutePreviewResponse(model.id(), false, null, warnings);
    }

    @GetMapping("/credentials")
    @Transactional(readOnly = true)
    public List<ProviderCredentialResponse> listCredentials() {
        UserEntity user = currentUserService.requireCurrentUser();
        return credentialRepository.findByUserIdOrderByCreatedAtDesc(user.getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @PostMapping("/credentials")
    @Transactional
    public ResponseEntity<ProviderCredentialResponse> storeCredential(@Valid @RequestBody CreateCredentialRequest request) {
        UserEntity user = currentUserService.requireCurrentUser();
        String provider = request.provider().trim().toUpperCase();
        String rawKey = request.apiKey().trim();

        EnvelopeEncryptionService.EncryptedPayload encrypted = encryptionService.encrypt(rawKey);

        ProviderCredentialEntity entity = new ProviderCredentialEntity();
        entity.setUserId(user.getId());
        entity.setProvider(provider);
        entity.setKeyHint(EnvelopeEncryptionService.maskKeyHint(rawKey));
        entity.setCiphertext(encrypted.ciphertext());
        entity.setWrappedDek(encrypted.wrappedDek());
        entity.setKekKeyId(encrypted.kekKeyId());
        entity.setStatus("ACTIVE");
        ProviderCredentialEntity saved = credentialRepository.save(entity);

        auditService.record(user.getId(), null, "CREDENTIAL_STORED", "CREDENTIAL", saved.getId(),
                "{\"provider\":\"" + provider + "\",\"kekKeyId\":\"" + encrypted.kekKeyId() + "\"}");
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(saved));
    }

    @DeleteMapping("/credentials/{credentialId}")
    @Transactional
    public ResponseEntity<Void> revokeCredential(@PathVariable UUID credentialId) {
        UserEntity user = currentUserService.requireCurrentUser();
        ProviderCredentialEntity cred = credentialRepository.findByIdAndUserId(credentialId, user.getId())
                .orElseThrow(() -> ApiException.notFound("CREDENTIAL_NOT_FOUND", "Credential not found"));
        cred.setStatus("REVOKED");
        cred.setCiphertext("WIPED");
        cred.setWrappedDek("WIPED");
        cred.setRevokedAt(Instant.now());
        credentialRepository.save(cred);

        auditService.record(user.getId(), null, "CREDENTIAL_REVOKED", "CREDENTIAL", cred.getId(), null);
        return ResponseEntity.noContent().build();
    }

    private ProviderCredentialResponse toResponse(ProviderCredentialEntity entity) {
        return new ProviderCredentialResponse(
                entity.getId(),
                entity.getProvider(),
                entity.getKeyHint(),
                entity.getKekKeyId(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getRevokedAt()
        );
    }

    public record ModelDescriptorResponse(
            String id,
            String displayName,
            String provider,
            int contextLength,
            boolean supportsTools,
            boolean supportsJson,
            boolean supportsStreaming,
            String recommendedFallbackModel
    ) {}

    public record RoutePreviewRequest(
            @NotBlank(message = "Requested model is required")
            String requestedModel,
            Boolean requireTools,
            Boolean requireJson,
            Boolean allowFallback
    ) {}

    public record RoutePreviewResponse(
            String selectedModel,
            boolean fallbackUsed,
            String fallbackReason,
            List<String> warnings
    ) {}

    public record CreateCredentialRequest(
            @NotBlank(message = "Provider name is required")
            @Size(max = 64)
            String provider,
            @NotBlank(message = "API key must not be blank")
            @Size(min = 8, max = 512, message = "API key length must be between 8 and 512 characters")
            String apiKey
    ) {}

    public record ProviderCredentialResponse(
            UUID id,
            String provider,
            String keyHint,
            String kekKeyId,
            String status,
            Instant createdAt,
            Instant revokedAt
    ) {}
}
