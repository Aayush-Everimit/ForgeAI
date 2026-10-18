package com.forgeai.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.api.security.ForgeAiPrincipal;
import com.forgeai.shared.domain.ProviderCredentialEntity;
import com.forgeai.shared.domain.UserEntity;
import com.forgeai.shared.repository.ProviderCredentialRepository;
import com.forgeai.shared.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ForgeAiApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProviderCredentialRepository credentialRepository;

    private UserEntity alice;
    private UserEntity bob;

    @BeforeEach
    void setUpUsers() {
        alice = userRepository.findByGithubIdAndDeletedAtIsNull("gh-alice")
                .orElseGet(() -> {
                    UserEntity u = new UserEntity();
                    u.setGithubId("gh-alice");
                    u.setGithubLogin("alice");
                    u.setDisplayName("Alice Architect");
                    return userRepository.save(u);
                });

        bob = userRepository.findByGithubIdAndDeletedAtIsNull("gh-bob")
                .orElseGet(() -> {
                    UserEntity u = new UserEntity();
                    u.setGithubId("gh-bob");
                    u.setGithubLogin("bob");
                    u.setDisplayName("Bob Builder");
                    return userRepository.save(u);
                });
    }

    private UsernamePasswordAuthenticationToken authFor(UserEntity user) {
        ForgeAiPrincipal principal = new ForgeAiPrincipal(
                user.getId(),
                user.getGithubId(),
                user.getGithubLogin(),
                user.getDisplayName()
        );
        return new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
    }

    @Test
    void healthEndpointIsPublicAndReturnsJavaSpringBootStatus() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.service").value("api"))
                .andExpect(jsonPath("$.runtime").value("java-spring-boot"));
    }

    @Test
    void unauthenticatedRequestsToApiAreRejectedWith401StructuredError() throws Exception {
        mockMvc.perform(get("/api/v1/projects"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void stateChangingRequestsWithoutCsrfTokenAreRejectedWith403() throws Exception {
        mockMvc.perform(post("/api/v1/projects")
                        .with(authentication(authFor(alice)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"No CSRF Project\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void validationErrorsReturnStructured400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/v1/projects")
                        .with(authentication(authFor(alice)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.name").isNotEmpty());
    }

    @Test
    void projectCrudAndCrossTenantIsolationPreventsBobFromAccessingAliceProject() throws Exception {
        MvcResult createRes = mockMvc.perform(post("/api/v1/projects")
                        .with(authentication(authFor(alice)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "ForgeAI Migration",
                                  "description": "Spring Boot backend",
                                  "stackMetadata": {"language": "Java 21"}
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("ForgeAI Migration"))
                .andReturn();

        JsonNode created = objectMapper.readTree(createRes.getResponse().getContentAsString());
        String projectId = created.get("id").asText();

        // Bob cannot read, update, or delete Alice's project
        mockMvc.perform(get("/api/v1/projects/{id}", projectId)
                        .with(authentication(authFor(bob))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));

        mockMvc.perform(patch("/api/v1/projects/{id}", projectId)
                        .with(authentication(authFor(bob)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Bob Hijack\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/projects/{id}", projectId)
                        .with(authentication(authFor(bob)))
                        .with(csrf()))
                .andExpect(status().isNotFound());

        // Alice can update her project
        mockMvc.perform(patch("/api/v1/projects/{id}", projectId)
                        .with(authentication(authFor(alice)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Updated description\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Updated description"))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void knowledgeReviewApprovalVersioningAndConflictDetection() throws Exception {
        String projectId = createProjectForAlice("Knowledge Test Project");

        // 1. Create an approved architectural decision
        MvcResult dec1Res = mockMvc.perform(post("/api/v1/projects/{id}/knowledge", projectId)
                        .with(authentication(authFor(alice)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "kind": "DECISION",
                                  "title": "Primary Database",
                                  "content": "PostgreSQL is authoritative.",
                                  "sourceType": "MANUAL",
                                  "autoApprove": true
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andReturn();
        String firstDecisionId = objectMapper.readTree(dec1Res.getResponse().getContentAsString()).get("id").asText();

        // 2. Propose conflicting decision with same title -> surfaces conflict warning and stays PROPOSED
        MvcResult dec2Res = mockMvc.perform(post("/api/v1/projects/{id}/knowledge", projectId)
                        .with(authentication(authFor(alice)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "kind": "DECISION",
                                  "title": "Primary Database",
                                  "content": "PostgreSQL 16 with Flyway migrations is authoritative.",
                                  "sourceType": "AI_PROPOSAL",
                                  "autoApprove": true
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PROPOSED"))
                .andExpect(jsonPath("$.conflictWarnings[0]").exists())
                .andReturn();
        String secondDecisionId = objectMapper.readTree(dec2Res.getResponse().getContentAsString()).get("id").asText();

        // 3. Approve second decision superseding first decision -> versions history without silent overwrite
        mockMvc.perform(post("/api/v1/projects/{id}/knowledge/{itemId}/review", projectId, secondDecisionId)
                        .with(authentication(authFor(alice)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "action": "APPROVE",
                                  "supersedesItemId": "%s",
                                  "reviewNotes": "Verified Flyway requirement"
                                }
                                """.formatted(firstDecisionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.supersedesId").value(firstDecisionId));
    }

    @Test
    void handoffLifecycleCanonicalPromptExtensionAndDeletionSchedulesJobs() throws Exception {
        String projectId = createProjectForAlice("Handoff Lifecycle Project");

        // Add approved constraint
        mockMvc.perform(post("/api/v1/projects/{id}/knowledge", projectId)
                        .with(authentication(authFor(alice)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "kind": "CONSTRAINT",
                                  "title": "No Paid Operator Keys",
                                  "content": "Users must supply BYOK keys.",
                                  "sourceType": "MANUAL",
                                  "autoApprove": true
                                }
                                """))
                .andExpect(status().isCreated());

        // Generate handoff
        MvcResult handoffRes = mockMvc.perform(post("/api/v1/projects/{id}/handoffs", projectId)
                        .with(authentication(authFor(alice)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "task": "Implement durable worker retry logic",
                                  "taskType": "feature",
                                  "role": "Backend Architect"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn();

        JsonNode handoffJson = objectMapper.readTree(handoffRes.getResponse().getContentAsString());
        String handoffId = handoffJson.get("id").asText();
        String promptBody = handoffJson.get("promptBody").asText();

        assertThat(promptBody).contains("## 1. Role", "## 2. Task", "## 3. Context", "## 4. Constraints and Instructions", "## 5. Output and Success Criteria");
        assertThat(promptBody).contains("No Paid Operator Keys");

        // Extend handoff -> increments version to 2
        mockMvc.perform(post("/api/v1/projects/{id}/handoffs/{handoffId}/extend", projectId, handoffId)
                        .with(authentication(authFor(alice)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"extensionDays\": 10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.extendedAt").isNotEmpty());

        // Delete handoff -> schedules cleanup job
        mockMvc.perform(delete("/api/v1/projects/{id}/handoffs/{handoffId}", projectId, handoffId)
                        .with(authentication(authFor(alice)))
                        .with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("DELETED"))
                .andExpect(jsonPath("$.cleanupJobId").isNotEmpty());
    }

    @Test
    void providerCredentialEncryptionAndVisibleModelFallback() throws Exception {
        String rawSecretKey = "sk-or-v1-super-secret-plaintext-key-9999";

        MvcResult credRes = mockMvc.perform(post("/api/v1/providers/credentials")
                        .with(authentication(authFor(alice)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "provider": "OPENROUTER",
                                  "apiKey": "%s"
                                }
                                """.formatted(rawSecretKey)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.keyHint").value("sk-o...9999"))
                .andExpect(jsonPath("$.ciphertext").doesNotExist())
                .andReturn();

        String responseBody = credRes.getResponse().getContentAsString();
        assertThat(responseBody).doesNotContain(rawSecretKey);

        UUID credId = UUID.fromString(objectMapper.readTree(responseBody).get("id").asText());
        ProviderCredentialEntity stored = credentialRepository.findById(credId).orElseThrow();
        assertThat(stored.getCiphertext()).isNotEqualTo(rawSecretKey);

        // Request a model that lacks tool support when requireTools=true -> discloses visible fallback
        mockMvc.perform(post("/api/v1/providers/route-preview")
                        .with(authentication(authFor(alice)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestedModel": "deepseek/deepseek-chat-v3",
                                  "requireTools": true,
                                  "allowFallback": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fallbackUsed").value(true))
                .andExpect(jsonPath("$.fallbackReason").value("CAPABILITY_MISMATCH"))
                .andExpect(jsonPath("$.selectedModel").value("anthropic/claude-3.7-sonnet"));
    }

    @Test
    void sourceRegistrationAndDeletionEnqueuesDurableJobs() throws Exception {
        String projectId = createProjectForAlice("Source Lifecycle Project");

        MvcResult regRes = mockMvc.perform(post("/api/v1/projects/{id}/sources", projectId)
                        .with(authentication(authFor(alice)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceType": "DOCUMENT",
                                  "sourceReference": "docs/02-system-architecture.md",
                                  "content": "# System Architecture\\nJava 21 + Spring Boot owns product workflows."
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.ingestionStatus").value("PENDING"))
                .andExpect(jsonPath("$.jobId").isNotEmpty())
                .andReturn();

        String sourceId = objectMapper.readTree(regRes.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(delete("/api/v1/projects/{id}/sources/{sourceId}", projectId, sourceId)
                        .with(authentication(authFor(alice)))
                        .with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("DELETING"))
                .andExpect(jsonPath("$.cleanupJobId").isNotEmpty());

        mockMvc.perform(get("/api/v1/projects/{id}/jobs", projectId)
                        .with(authentication(authFor(alice))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    private String createProjectForAlice(String name) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/projects")
                        .with(authentication(authFor(alice)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText();
    }
}
