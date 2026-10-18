/**
 * Typed HTTP client and contracts for the ForgeAI Spring Boot API (/api/v1).
 * Never exposes provider API keys or internal service tokens to the browser.
 */

export interface ApiErrorResponse {
  code: string;
  message: string;
  retryable: boolean;
  requestId: string;
  fieldErrors?: Record<string, string>;
  timestamp: string;
}

export interface UserProfile {
  id: string;
  githubId: string;
  githubLogin: string;
  displayName: string;
  avatarUrl?: string;
  status: string;
  createdAt: string;
}

export interface Project {
  id: string;
  ownerUserId: string;
  name: string;
  description?: string;
  stackMetadata: Record<string, unknown>;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface KnowledgeItem {
  id: string;
  projectId: string;
  kind: "NOTE" | "PROGRESS" | "DECISION" | "CONSTRAINT" | "ISSUE" | "SUMMARY";
  title: string;
  content: string;
  status: "PROPOSED" | "APPROVED" | "REJECTED" | "SUPERSEDED" | "DELETED";
  sourceType: string;
  sourceReference?: string;
  contentHash: string;
  approvedBy?: string;
  approvedAt?: string;
  reviewNotes?: string;
  supersedesId?: string;
  version: number;
  conflictWarnings: string[];
  createdAt: string;
  updatedAt: string;
}

export interface ValidatedEvidenceChunk {
  chunkId: string;
  sourceId: string;
  sourceReference: string;
  content: string;
  score: number;
  contentHash: string;
}

export interface Handoff {
  id: string;
  projectId: string;
  createdBy: string;
  task: string;
  taskType: string;
  role: string;
  targetModel?: string;
  promptBody: string;
  warnings: string[];
  sources: ValidatedEvidenceChunk[];
  status: "ACTIVE" | "EXPIRED" | "DELETED";
  reminderSentAt?: string;
  createdAt: string;
  updatedAt: string;
  expiresAt: string;
  extendedAt?: string;
  version: number;
}

export interface ModelDescriptor {
  id: string;
  displayName: string;
  provider: string;
  contextLength: number;
  supportsTools: boolean;
  supportsJson: boolean;
  supportsStreaming: boolean;
  recommendedFallbackModel: string;
}

const API_BASE_URL =
  process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:4000";

function readCsrfCookie(): string | undefined {
  if (typeof document === "undefined") {
    return undefined;
  }
  const match = document.cookie
    .split("; ")
    .find((row) => row.startsWith("XSRF-TOKEN="));
  return match ? decodeURIComponent(match.split("=")[1]) : undefined;
}

export async function apiRequest<T>(
  path: string,
  init: RequestInit = {}
): Promise<T> {
  const headers = new Headers(init.headers);
  if (!headers.has("Content-Type") && init.body) {
    headers.set("Content-Type", "application/json");
  }
  const csrf = readCsrfCookie();
  if (csrf && init.method && !["GET", "HEAD", "OPTIONS"].includes(init.method.toUpperCase())) {
    headers.set("X-XSRF-TOKEN", csrf);
  }

  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...init,
    headers,
    credentials: "include",
  });

  if (!response.ok) {
    let errorBody: ApiErrorResponse | undefined;
    try {
      errorBody = (await response.json()) as ApiErrorResponse;
    } catch {
      // fallback if non-JSON error
    }
    throw new Error(
      errorBody?.message ?? `API request failed with status ${response.status}`
    );
  }

  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

export const forgeAiApi = {
  getGitHubLoginUrl(): string {
    return `${API_BASE_URL}/api/v1/auth/github/login`;
  },
  getCurrentUser(): Promise<UserProfile> {
    return apiRequest<UserProfile>("/api/v1/auth/me");
  },
  listProjects(): Promise<Project[]> {
    return apiRequest<Project[]>("/api/v1/projects");
  },
  listKnowledge(projectId: string): Promise<KnowledgeItem[]> {
    return apiRequest<KnowledgeItem[]>(`/api/v1/projects/${projectId}/knowledge`);
  },
  listHandoffs(projectId: string): Promise<Handoff[]> {
    return apiRequest<Handoff[]>(`/api/v1/projects/${projectId}/handoffs`);
  },
  listModels(): Promise<ModelDescriptor[]> {
    return apiRequest<ModelDescriptor[]>("/api/v1/providers/models");
  },
};
