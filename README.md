# Projects Knowledge backend

Standalone Java 21 / Spring Boot 3.5 service for a local repository knowledge explorer. The Angular UI is maintained separately as `projects-knowledge-frontend`; no frontend checkout is needed to build this backend.

## Requirements

- JDK 21, with `JAVA_HOME` pointing to that installation.
- Codex installed and signed in on the machine running this service.
- Network access on the first Maven run to download Maven and dependencies.

The Maven wrapper is included, including `.mvn/wrapper/maven-wrapper.jar`. A separate Maven installation is not required.

## Run locally

From this repository's root in PowerShell:

```powershell
$env:SERVER_ADDRESS = '127.0.0.1'
.\mvnw.cmd spring-boot:run
```

The API runs on `http://localhost:8090`. Start the separate frontend on port `4300`; its development proxy forwards `/api` requests here, so no shared checkout or CORS changes are required.

On macOS/Linux, use the installed Codex executable name and the shell wrapper:

```sh
CODEX_COMMAND=codex SERVER_ADDRESS=127.0.0.1 sh ./mvnw spring-boot:run
```

## Verify and package

```powershell
.\mvnw.cmd test
.\mvnw.cmd package
java -jar target/backend-0.0.1-SNAPSHOT.jar
```

On macOS/Linux, replace `.\mvnw.cmd` with `sh ./mvnw`. Packaging does not require the frontend.

## Build the Windows desktop distribution

The frontend and backend remain separate repositories. The release script expects the frontend checkout in the sibling `../frontend` directory, builds Angular, embeds its production files in the Spring Boot JAR, and then uses JDK `jpackage` to bundle Java with the application.

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
.\packaging\build-windows.ps1
```

The build machine also needs a Node version supported by the checked-in Angular CLI. When a different Node executable should be used without changing the system installation, pass `-NodePath C:\path\to\node.exe`. Frontend dependencies are installed in an isolated backend `target` directory, so a running frontend development server is not touched.

Outputs are written to the ignored `release` directory:

- `ProjectsKnowledge-Portable-1.0.0.zip`: extract the whole folder and run `ProjectsKnowledge.exe`; Java and Node are not required on the destination PC.
- `ProjectsKnowledge-Setup-1.0.0.exe`: per-user Windows installer, created when WiX 3 `candle.exe` and `light.exe` are available or supplied with `-WixPath`.

After a successful build, the portable ZIP is also copied to the current user's Desktop and replaces the previous file with the same version. Pass `-SkipDesktopCopy` when that extra copy is not wanted.

The desktop launcher opens `http://127.0.0.1:8090/`. Use **Close app** in the header to stop the local server, or **Clear cache** to delete every in-memory and persistent cache; both actions require confirmation and are accepted only from the loopback interface. A **Projects Knowledge** tray icon with Open and Exit actions is also added when Windows exposes system-tray support. Codex is deliberately not bundled: each user must install Codex, sign in with their own account, and keep the repositories they want to query on their computer. The default server address is loopback-only.

## Configuration

Operational defaults are in `src/main/resources/application.yml`. Override them through environment variables or an ignored local Spring configuration file; never commit credentials or a personal project catalog.

| Setting | Default |
| --- | --- |
| `SERVER_PORT` | `8090` |
| `CODEX_COMMAND` | `codex.exe` (Windows); use `codex` on other systems |
| `CODEX_MODEL` | empty; follows the default model reported by the installed Codex runtime |
| `CODEX_REASONING_EFFORT` | `medium` |
| Saved Codex selection | `%LOCALAPPDATA%/ProjectsKnowledge/codex-settings.json` |
| Codex model catalog cache | 18,000 seconds (5 hours) |
| Project catalog cache | 86,400 seconds (24 hours) |
| Question answer cache | 86,400 seconds (24 hours) |
| Integration detail cache | 86,400 seconds (24 hours) |
| Project overview cache | 86,400 seconds (24 hours) |
| Persistent cache | enabled; `%LOCALAPPDATA%/ProjectsKnowledge/cache/knowledge.db` on Windows |

Projects and repository roots come dynamically from Codex workspaces. This backend does not ship a fixed list of project names, repository paths, package namespaces, or integration vendors. The Codex model catalog, successful answers, integration details and project overviews are stored in a bounded local SQLite cache, so restarting the service does not require the same Codex requests again. Set `PROJECTS_KNOWLEDGE_STORAGE_PERSISTENT_CACHE_ENABLED=false` to use memory only, or override `PROJECTS_KNOWLEDGE_STORAGE_PERSISTENT_CACHE_PATH`. The cache file stays outside the repository by default and must never be committed.

## Codex connection lifecycle

The backend lazily starts one private `codex app-server --listen stdio://` process and initializes it once. Catalog requests and independent question/overview conversations reuse that connection. There is no model warm-up request and no conversation history is reused: each analysis still creates a fresh, ephemeral, read-only thread. The model follows Codex's reported default unless `CODEX_MODEL` is set; reasoning effort defaults to `medium` and can be overridden with `CODEX_REASONING_EFFORT`. A selection saved from the web UI takes precedence and is stored separately from disposable caches.

- Request IDs route RPC replies; thread and turn IDs isolate concurrent answers. A catalog request does not wait for another question to finish.
- Completed threads are unsubscribed using the [official OpenAI app-server protocol](https://learn.chatgpt.com/docs/app-server#unsubscribe-from-a-loaded-thread). Codex controls when unsubscribed threads are unloaded.
- A disconnected or failed connection is replaced on the **next** request. Questions are never automatically replayed after an ambiguous failure, avoiding duplicate model usage.
- A transport timeout, interruption, malformed protocol message or failed cleanup resets the private process. Other in-flight requests on that connection can fail too; the UI's retry action is explicit. A completed answer is retained if only its cleanup fails.
- Setup/RPC waits are capped at 30 seconds (or the configured timeout, if lower); answer acknowledgement and completion share the configured 300-second deadline. Cleanup has a 2-second deadline. Shutting down the backend terminates its private process.
- Logs contain connection setup and per-analysis `setupMs`/`turnMs` timings, not prompts, answers, repository paths or raw Codex logs. Connection reuse removes repeated startup overhead; it does not guarantee faster repository analysis.

Normal tests use an in-memory JSONL peer. An optional local smoke test checks connection reuse and ephemeral-thread cleanup without any model turns:

```powershell
$env:CODEX_TRANSPORT_SMOKE_TEST = 'true'
.\mvnw.cmd '-Dtest=CodexConnectionSmokeTest' test
Remove-Item Env:CODEX_TRANSPORT_SMOKE_TEST
```

## API

- `GET /api/projects`: list available projects without model analysis.
- `GET /api/codex/status`: check connection, authentication readiness, selected model, reasoning effort and active turn count without starting a model turn or exposing account identifiers.
- `GET /api/codex/settings`: load the available runtime models and their supported reasoning efforts without starting a model turn.
- `PUT /api/codex/settings`: validate and persist the model and reasoning effort used by new analyses.
- `POST /api/projects/refresh`: bypass the catalog cache and reload available projects without model analysis; overview caches are unchanged.
- `GET /api/projects/{id}`: get the analyzed, cached project overview.
- `POST /api/projects/{id}/overview/refresh`: run a fresh overview analysis.
- `POST /api/questions`: submit a question.
- `POST /api/integrations/details`: get integration details.
- `GET /api/sources/content`: get a validated, redacted source range.

Use a project ID returned by `/api/projects`, or `all` for the combined scope. A question request has this shape:

```json
{
  "projectId": "<project id>",
  "question": "How does this project's approval workflow work?",
  "language": "en",
  "mode": "workflow"
}
```

Supported languages: `en`, `ar`. Modes: `basic` (summary), `advanced` (technical details and sources), `workflow` (roles, steps, example and diagram), `database` (tables, relevant columns, keys, relationships and data access). The frontend defaults to Basic; omitting the API mode retains Advanced behavior. Answers are cached separately by project/repositories, question, language and mode.

`POST /api/questions/refresh` accepts the same question body and bypasses a cached answer. Integration details support the equivalent `POST /api/integrations/details/refresh`. Responses include server-generated `updatedAt` and `expiresAt` timestamps: 24 hours begin after analysis finishes, and cache hits do not extend them. Identical in-flight analyses are shared. Failed or cancelled refreshes preserve both the memory and disk snapshots. The SQLite cache is bounded by namespace and separated by project identity and repository paths. Cache reads deliberately avoid rescanning repository files; source changes appear after expiry or an explicit refresh. Refresh reruns analysis and consumes model usage.

Database mode inspects repository schema/migrations and data-access code, not a live database. Its dedicated output contains a summary, key findings, up to 6 tables with relevant columns/relationships, caveats and source evidence. It does not execute SQL, generate scripts or request unrelated API/workflow sections. Missing physical names and constraints must remain unverified. The extra `database[].columns` and `database[].relationships` lists default to empty for older Advanced results. The Codex effort stays `medium`.

## Request cancellation

The frontend assigns a fresh UUID in `X-Request-ID` to each API request. `POST /api/requests/{id}/cancel` acknowledges cancellation with HTTP 204 and also handles cancellation arriving before the original request. Requests without this header remain compatible.

The browser aborts immediately. Backend analyses cooperate with cancellation; an active Codex turn uses [`turn/interrupt`](https://learn.chatgpt.com/docs/app-server#interrupt-a-turn) with its own thread/turn IDs. Identical analyses remain shared while another caller still needs the result. Cancelled or failed refreshes retain the previous successful cache. Setup RPCs finish their bounded acknowledgement before interrupting a newly started turn; cancellation cannot recover usage already consumed. If interruption cannot be confirmed, the private backend-owned Codex process is reset as a safety fallback.

Cancellation IDs are random request capabilities, not user authentication. Keep this local-only service protected as described below.

## Security and scope

This service accesses local repositories through read-only analysis and validates source-viewer paths and ranges. Scope classification and answer grounding are model-based and are not deterministic security boundaries. Source-viewer redaction does not filter all repository content read by Codex.

There is no built-in public-user authentication. Keep the service bound to localhost or protected inside a trusted environment; do not expose it directly to the Internet. Do not upload Codex authentication, local configuration, repository data, logs or generated answers to GitHub.

## Code organization

The package layout follows the Public Marts backend conventions, using this application's own domain and namespace:

```text
src/main/java/com/projectsknowledge/
  business/
    project/    catalog, controller, entity, enums, mapper, schema/response, service/impl
    knowledge/  controller, enums, mapper, schema/request, schema/response, service/impl
    source/     controller, schema/response, service/impl
  general/
    config/     application properties and the shared clock
    exception/  API error handling
    integration/codex/  client and structured response schemas
    scanner/    safe repository discovery and source access
    security/   source redaction
```

- Controllers depend on service **interfaces**; Spring implementations live in `service/impl` and use Lombok constructor injection.
- Request/response names use `Req...` / `Dto...`. Response records support Lombok builders and retain their existing immutable field bindings. Request records preserve validation and default-mode normalization.
- Stateless mapping lives in final `*Mapper` classes with static methods. Runtime project entities use Lombok accessors; they are not database entities.
- Tests mirror the production packages. `.editorconfig` defines the basic Java formatting conventions.
- Keep project discovery dynamic. Do not copy reference-project names, repository paths, business rules, credentials, database layers or authorization setup into this application.

This is a structural refactor: Java 21, Spring Boot, Maven, `/api` URLs, JSON fields and cache behavior remain unchanged. No database transactions or response-envelope layer are added to a service that does not need them.

## GitHub

Publish this folder as its own repository. Commit `src`, `pom.xml`, the Maven wrapper files and `.mvn/wrapper`; exclude `target`, local IDE settings and credentials using the included `.gitignore`. Keep the companion frontend in its own repository.
