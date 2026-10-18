import styles from "./page.module.css";
import { forgeAiApi } from "../lib/api";

export default function Home() {
  const loginUrl = forgeAiApi.getGitHubLoginUrl();

  return (
    <div className={styles.page}>
      <main className={styles.main}>
        <div className={styles.intro}>
          <h1>ForgeAI Engineering Workspace</h1>
          <p>
            Persistent approved project context, source-grounded RAG evidence,
            and portable 5-part handoff prompts powered by the{" "}
            <code className={styles.code}>Java 21 + Spring Boot</code> API (
            <code className={styles.code}>/api/v1</code>) and{" "}
            <code className={styles.code}>Python FastAPI</code> RAG service.
          </p>
        </div>
        <div className={styles.ctas}>
          <a className={styles.primary} href={loginUrl}>
            Sign in with GitHub OAuth
          </a>
          <a
            className={styles.secondary}
            href="http://localhost:4000/health"
            target="_blank"
            rel="noopener noreferrer"
          >
            API Health Probe
          </a>
        </div>
      </main>
    </div>
  );
}
