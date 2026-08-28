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

## Configuration

Operational defaults are in `src/main/resources/application.yml`. Override them through environment variables or an ignored local Spring configuration file; never commit credentials or a personal project catalog.

| Setting | Default |
| --- | --- |
| `SERVER_PORT` | `8090` |
| `CODEX_COMMAND` | `codex.exe` (Windows); use `codex` on other systems |
| Project catalog cache | 3,600 seconds (1 hour) |
| Question answer cache | 900 seconds |
| Integration detail cache | 18,000 seconds (5 hours) |
| Project overview cache | 18,000 seconds (5 hours) |

Projects and repository roots come dynamically from Codex workspaces. This backend does not ship a fixed list of project names, repository paths, package namespaces, or integration vendors. Restarting the service clears its in-memory caches.

## API

- `GET /api/projects`: list available projects without model analysis.
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

Supported languages: `en`, `ar`. Modes: `basic` (summary), `advanced` (technical details and sources), `workflow` (roles, steps, example and diagram). The frontend defaults to Basic; omitting the API mode retains Advanced behavior. Answers are cached separately by project/repositories, question, language and mode.

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
