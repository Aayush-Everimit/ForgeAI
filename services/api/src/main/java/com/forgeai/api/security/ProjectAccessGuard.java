package com.forgeai.api.security;

import com.forgeai.api.common.ApiException;
import com.forgeai.shared.domain.ProjectEntity;
import com.forgeai.shared.domain.UserEntity;
import com.forgeai.shared.repository.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Enforces strict server-side tenant isolation for every project-scoped operation.
 * Returns 404 Not Found when a project does not exist or belongs to another user to prevent IDOR enumeration.
 */
@Service
public class ProjectAccessGuard {

    private final ProjectRepository projectRepository;
    private final CurrentUserService currentUserService;

    public ProjectAccessGuard(ProjectRepository projectRepository, CurrentUserService currentUserService) {
        this.projectRepository = projectRepository;
        this.currentUserService = currentUserService;
    }

    @Transactional(readOnly = true)
    public AuthorizedProjectContext requireOwnedProject(UUID projectId) {
        UserEntity user = currentUserService.requireCurrentUser();
        ProjectEntity project = projectRepository.findByIdAndOwnerUserIdAndDeletedAtIsNull(projectId, user.getId())
                .orElseThrow(() -> ApiException.notFound("PROJECT_NOT_FOUND", "Project not found or not accessible"));
        return new AuthorizedProjectContext(user, project);
    }

    public record AuthorizedProjectContext(UserEntity user, ProjectEntity project) {}
}
