package com.forgeai.api.security;

import com.forgeai.api.common.ApiException;
import com.forgeai.shared.domain.UserEntity;
import com.forgeai.shared.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class CurrentUserService {

    private final UserRepository userRepository;

    public CurrentUserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public UserEntity requireCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication required", false);
        }

        Object principal = auth.getPrincipal();
        if (principal instanceof ForgeAiPrincipal fp) {
            return userRepository.findByIdAndDeletedAtIsNull(fp.userId())
                    .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "USER_NOT_FOUND", "Authenticated user not found or deleted", false));
        }

        if (principal instanceof OAuth2User oauth2User) {
            Object idAttr = oauth2User.getAttribute("id");
            String githubId = idAttr != null ? String.valueOf(idAttr) : auth.getName();
            String login = oauth2User.getAttribute("login") != null ? String.valueOf(oauth2User.getAttribute("login")) : githubId;
            String name = oauth2User.getAttribute("name") != null ? String.valueOf(oauth2User.getAttribute("name")) : login;
            String avatar = oauth2User.getAttribute("avatar_url") != null ? String.valueOf(oauth2User.getAttribute("avatar_url")) : null;

            return userRepository.findByGithubIdAndDeletedAtIsNull(githubId)
                    .orElseGet(() -> {
                        UserEntity u = new UserEntity();
                        u.setGithubId(githubId);
                        u.setGithubLogin(login);
                        u.setDisplayName(name);
                        u.setAvatarUrl(avatar);
                        u.setStatus("ACTIVE");
                        return userRepository.save(u);
                    });
        }

        try {
            UUID userId = UUID.fromString(auth.getName());
            return userRepository.findByIdAndDeletedAtIsNull(userId)
                    .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "USER_NOT_FOUND", "Authenticated user not found", false));
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Unsupported authentication principal", false);
        }
    }
}
