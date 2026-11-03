package com.forgeai.api.security;

import java.io.Serializable;
import java.security.Principal;
import java.util.UUID;

public record ForgeAiPrincipal(
        UUID userId,
        String githubId,
        String githubLogin,
        String displayName
) implements Principal, Serializable {

    @Override
    public String getName() {
        return userId.toString();
    }
}
