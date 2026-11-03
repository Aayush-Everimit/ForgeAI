"use client";

import { FormEvent, useCallback, useEffect, useState } from "react";
import styles from "../page.module.css";
import {
  forgeAiApi,
  type Handoff,
  type KnowledgeItem,
  type Project,
  type UserProfile,
} from "../../lib/api";

const KINDS: KnowledgeItem["kind"][] = [
  "DECISION",
  "CONSTRAINT",
  "PROGRESS",
  "ISSUE",
  "NOTE",
  "SUMMARY",
];

export default function WorkspacePage() {
  const [user, setUser] = useState<UserProfile | null>(null);
  const [projects, setProjects] = useState<Project[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [knowledge, setKnowledge] = useState<KnowledgeItem[]>([]);
  const [handoffs, setHandoffs] = useState<Handoff[]>([]);
  const [activeHandoff, setActiveHandoff] = useState<Handoff | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [ready, setReady] = useState(false);

  const [projectName, setProjectName] = useState("ForgeAI Demo");
  const [projectDescription, setProjectDescription] = useState(
    "Local workspace used to show approved context and a portable handoff."
  );
  const [kind, setKind] = useState<KnowledgeItem["kind"]>("DECISION");
  const [title, setTitle] = useState("Authoritative store");
  const [content, setContent] = useState(
    "PostgreSQL is the source of truth. Generated prompts must cite approved knowledge and must not invent missing facts."
  );
  const [task, setTask] = useState(
    "Add a read-only project summary endpoint that includes approved decisions and constraints."
  );

  const loadProject = useCallback(async (projectId: string) => {
    const [items, prompts] = await Promise.all([
      forgeAiApi.listKnowledge(projectId),
      forgeAiApi.listHandoffs(projectId),
    ]);
    setKnowledge(items);
    setHandoffs(prompts);
    setActiveHandoff(prompts[0] ?? null);
  }, []);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const current = await forgeAiApi.getCurrentUser();
        const listed = await forgeAiApi.listProjects();
        if (cancelled) return;
        setUser(current);
        setProjects(listed);
        if (listed[0]) {
          setSelectedId(listed[0].id);
          await loadProject(listed[0].id);
        }
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : "Could not load the workspace");
        }
      } finally {
        if (!cancelled) setReady(true);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [loadProject]);

  async function run(action: () => Promise<void>) {
    setBusy(true);
    setError(null);
    try {
      await action();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Request failed");
    } finally {
      setBusy(false);
    }
  }

  function onCreateProject(event: FormEvent) {
    event.preventDefault();
    void run(async () => {
      const created = await forgeAiApi.createProject({
        name: projectName,
        description: projectDescription,
        stackMetadata: { languages: ["Java"], frameworks: ["Spring Boot"] },
      });
      const listed = await forgeAiApi.listProjects();
      setProjects(listed);
      setSelectedId(created.id);
      await loadProject(created.id);
    });
  }

  function onCreateKnowledge(event: FormEvent) {
    event.preventDefault();
    if (!selectedId) return;
    void run(async () => {
      await forgeAiApi.createKnowledge(selectedId, {
        kind,
        title,
        content,
        sourceType: "MANUAL",
        autoApprove: true,
      });
      await loadProject(selectedId);
    });
  }

  function onCreateHandoff(event: FormEvent) {
    event.preventDefault();
    if (!selectedId) return;
    void run(async () => {
      const created = await forgeAiApi.createHandoff(selectedId, {
        task,
        taskType: "feature",
        role: "Senior Software Engineer",
        contextTokenBudget: 6000,
        executeInference: false,
      });
      setActiveHandoff(created);
      await loadProject(selectedId);
      setActiveHandoff(created);
    });
  }

  if (!ready) {
    return (
      <div className={styles.page}>
        <main className={styles.main}>
          <p className={styles.meta}>Loading workspace…</p>
        </main>
      </div>
    );
  }

  if (!user) {
    return (
      <div className={styles.page}>
        <main className={styles.main}>
          <div className={styles.intro}>
            <h1>Sign in required</h1>
            <p>Start the API with the demo profile, then open the local demo session.</p>
            {error ? <p className={styles.error}>{error}</p> : null}
          </div>
          <div className={styles.ctas}>
            <a className={styles.primary} href={forgeAiApi.getDevLoginUrl()}>
              Enter local demo
            </a>
          </div>
        </main>
      </div>
    );
  }

  return (
    <div className={`${styles.page} ${styles.workspace}`}>
      <main className={`${styles.main} ${styles.workspaceMain}`}>
        <div className={styles.intro}>
          <h1>Workspace</h1>
          <p>
            Signed in as <strong>{user.displayName}</strong> ({user.githubLogin}). Create a
            project, approve a knowledge item, then generate a five-part handoff.
          </p>
        </div>

        {error ? <p className={styles.error}>{error}</p> : null}

        <form className={styles.panel} onSubmit={onCreateProject}>
          <h2>Project</h2>
          <div className={styles.row}>
            <input
              className={styles.field}
              value={projectName}
              onChange={(e) => setProjectName(e.target.value)}
              placeholder="Project name"
              required
              maxLength={120}
            />
            <button className={styles.primary} type="submit" disabled={busy}>
              Create project
            </button>
          </div>
          <textarea
            className={styles.textarea}
            value={projectDescription}
            onChange={(e) => setProjectDescription(e.target.value)}
            maxLength={2000}
          />
          <div className={styles.list}>
            {projects.map((project) => (
              <button
                key={project.id}
                type="button"
                className={`${styles.item} ${project.id === selectedId ? styles.itemActive : ""}`}
                onClick={() => {
                  setSelectedId(project.id);
                  void run(() => loadProject(project.id));
                }}
              >
                <strong>{project.name}</strong>
                <div className={styles.meta}>{project.description || "No description"}</div>
              </button>
            ))}
            {projects.length === 0 ? <p className={styles.meta}>No projects yet.</p> : null}
          </div>
        </form>

        <form className={styles.panel} onSubmit={onCreateKnowledge}>
          <h2>Approved knowledge</h2>
          <div className={styles.row}>
            <select
              className={styles.select}
              value={kind}
              onChange={(e) => setKind(e.target.value as KnowledgeItem["kind"])}
            >
              {KINDS.map((value) => (
                <option key={value} value={value}>
                  {value}
                </option>
              ))}
            </select>
            <input
              className={styles.field}
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder="Title"
              required
              maxLength={200}
            />
            <button className={styles.primary} type="submit" disabled={busy || !selectedId}>
              Save approved
            </button>
          </div>
          <textarea
            className={styles.textarea}
            value={content}
            onChange={(e) => setContent(e.target.value)}
            required
            maxLength={20000}
          />
          <div className={styles.list}>
            {knowledge.map((item) => (
              <div key={item.id} className={styles.item}>
                <strong>
                  [{item.kind}] {item.title}
                </strong>
                <div className={styles.meta}>
                  {item.status} · {item.content}
                </div>
              </div>
            ))}
            {selectedId && knowledge.length === 0 ? (
              <p className={styles.meta}>No knowledge items for this project.</p>
            ) : null}
          </div>
        </form>

        <form className={styles.panel} onSubmit={onCreateHandoff}>
          <h2>Handoff</h2>
          <textarea
            className={styles.textarea}
            value={task}
            onChange={(e) => setTask(e.target.value)}
            required
            maxLength={4000}
          />
          <button className={styles.primary} type="submit" disabled={busy || !selectedId}>
            Generate handoff
          </button>
          {handoffs.length > 0 ? (
            <div className={styles.list}>
              {handoffs.map((handoff) => (
                <button
                  key={handoff.id}
                  type="button"
                  className={`${styles.item} ${activeHandoff?.id === handoff.id ? styles.itemActive : ""}`}
                  onClick={() => setActiveHandoff(handoff)}
                >
                  <strong>{handoff.task}</strong>
                  <div className={styles.meta}>
                    {handoff.status} · v{handoff.version}
                    {handoff.warnings.length ? ` · ${handoff.warnings.length} warning(s)` : ""}
                  </div>
                </button>
              ))}
            </div>
          ) : null}
          {activeHandoff ? (
            <>
              {activeHandoff.warnings.length > 0 ? (
                <p className={styles.meta}>{activeHandoff.warnings.join(" ")}</p>
              ) : null}
              <pre className={styles.prompt}>{activeHandoff.promptBody}</pre>
            </>
          ) : null}
        </form>
      </main>
    </div>
  );
}
