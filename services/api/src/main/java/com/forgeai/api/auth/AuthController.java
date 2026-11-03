package com.forgeai.api.auth;

import com.forgeai.api.common.ApiException;
import com.forgeai.api.security.CurrentUserService;
import com.forgeai.api.security.ForgeAiPrincipal;
import com.forgeai.shared.domain.UserEntity;
import com.forgeai.shared.repository.UserRepository;
import com.forgeai.shared.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String DEV_GITHUB_ID = "dev-local";

    private final CurrentUserService currentUserService;
    private final AuditService auditService;
    private final UserRepository userRepository;
    private final SecurityContextRepository securityContextRepository;
    private final boolean devAuthEnabled;
    private final String webOrigin;

    public AuthController(
            CurrentUserService currentUserService,
            AuditService auditService,
            UserRepository userRepository,
            SecurityContextRepository securityContextRepository,
            @Value("${forgeai.security.dev-auth-enabled:false}") boolean devAuthEnabled,
            @Value("${forgeai.web.allowed-origins:http://localhost:3000}") String allowedOrigins
    ) {
        this.currentUserService = currentUserService;
        this.auditService = auditService;
        this.userRepository = userRepository;
        this.securityContextRepository = securityContextRepository;
        this.devAuthEnabled = devAuthEnabled;
        this.webOrigin = allowedOrigins.split(",")[0].trim();
    }

    @GetMapping("/github/login")
    public void githubLogin(HttpServletResponse response) throws IOException {
        response.sendRedirect("/oauth2/authorization/github");
    }

    /**
     * Local demo sign-in. Enabled only when {@code forgeai.security.dev-auth-enabled=true}
     * (demo profile). Creates a stable local user and a session cookie, then returns to the web app.
     */
    @GetMapping("/dev/login")
    @Transactional
    public void devLogin(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!devAuthEnabled) {
            throw ApiException.notFound("DEV_AUTH_DISABLED", "Local demo login is disabled");
        }

        UserEntity user = userRepository.findByGithubIdAndDeletedAtIsNull(DEV_GITHUB_ID)
                .orElseGet(() -> {
                    UserEntity created = new UserEntity();
                    created.setGithubId(DEV_GITHUB_ID);
                    created.setGithubLogin("demo");
                    created.setDisplayName("Local Demo");
                    created.setStatus("ACTIVE");
                    return userRepository.save(created);
                });

        ForgeAiPrincipal principal = new ForgeAiPrincipal(
                user.getId(),
                user.getGithubId(),
                user.getGithubLogin(),
                user.getDisplayName()
        );
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        auditService.record(user.getId(), null, "AUTH_DEV_LOGIN", "USER", user.getId(), null);
        response.sendRedirect(webOrigin + "/workspace");
    }

    @GetMapping("/me")
    public UserResponse currentUser(CsrfToken csrfToken) {
        if (csrfToken != null) {
            csrfToken.getToken();
        }
        UserEntity user = currentUserService.requireCurrentUser();
        return new UserResponse(
                user.getId(),
                user.getGithubId(),
                user.getGithubLogin(),
                user.getDisplayName(),
                user.getAvatarUrl(),
                user.getStatus(),
                user.getCreatedAt()
        );
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        UserEntity user = currentUserService.requireCurrentUser();
        auditService.record(user.getId(), null, "AUTH_LOGOUT", "USER", user.getId(), null);
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    public record UserResponse(
            UUID id,
            String githubId,
            String githubLogin,
            String displayName,
            String avatarUrl,
            String status,
            Instant createdAt
    ) {}
}
