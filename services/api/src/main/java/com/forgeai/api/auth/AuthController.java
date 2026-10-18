package com.forgeai.api.auth;

import com.forgeai.api.security.CurrentUserService;
import com.forgeai.shared.domain.UserEntity;
import com.forgeai.shared.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final CurrentUserService currentUserService;
    private final AuditService auditService;

    public AuthController(CurrentUserService currentUserService, AuditService auditService) {
        this.currentUserService = currentUserService;
        this.auditService = auditService;
    }

    @GetMapping("/github/login")
    public void githubLogin(HttpServletResponse response) throws IOException {
        response.sendRedirect("/oauth2/authorization/github");
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
