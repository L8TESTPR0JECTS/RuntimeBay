# Workflow databases

Runtime Bay v0.5 integrates **CloudBeaver Community 26.2.2** as an optional local companion. Each project workflow has **Runtime** and **Databases** tabs. The Databases tab contains CloudBeaver’s navigator, database objects, table grid, filtering/sorting, staged editing, SQL editor, and query results. This is the upstream Community interface inside Runtime Bay, not a recreated SQL grid.

## Opening and connecting

1. Open a project workflow, choose **Databases**, then **Open database workspace**.
2. Docker must be running with a supported local Unix-socket engine. Runtime Bay downloads the official pinned image on first use, then starts a companion for that workflow. Startup is shown as an operation with progress; the shared breathing logo covers initial frame loading.
3. Add a connection through CloudBeaver’s **New Connection** dialog. It includes the drivers provided by the Community image. For containers published on this Mac, connect to `host.docker.internal` and the host-side port; `localhost` inside the companion refers to the companion itself.
4. Enter database credentials in the connection dialog. Saving them is an explicit CloudBeaver choice. Configure SSL using the database’s existing CA as appropriate; Runtime Bay does not automatically disable certificate verification or grant database permissions.

Database connections are separate from container links. Linking a container does not automatically disclose its credentials to CloudBeaver. The companion always runs locally; a database can be remote if it is reachable through the connection configuration supported by Community. This implementation does not automatically create SSH forwards from a linked remote Docker host.

## Edits, SQL, and lifetime

- Table-grid edits are local pending changes until **Save**. **Cancel** discards pending grid edits. A connection in manual transaction mode also requires **Commit** to persist transactional writes; **Rollback** discards those writes.
- SQL runs when the user chooses Execute. Auto-commit and manual commit follow the connection’s settings; DDL and nontransactional database operations can have database-specific commit behavior.
- SQL editor script autosave saves script text to the private workspace; it does not execute SQL or save pending table changes.
- Switching Runtime Bay views or workflows keeps existing embedded frames and companions alive. Reloading the application document reconnects to existing ready companions; frame state itself is reloaded.
- **Close database workspace** stops only that workflow’s companion. Closing Runtime Bay stops its database companions as well as its owned services and terminals. Existing MySQL/PostgreSQL servers and other linked containers are not stopped.
- Resolve pending edits and transactions before closing a companion or the app. Saved connections and scripts persist; live transactions do not survive a companion restart.

## Private gateway

The pinned image is `dbeaver/cloudbeaver:26.2.2@sha256:7298c59289e9519d8913083c85f3ceb475aa12db0f58e87b6491445a9b298d2c`. It is pulled only after an explicit workspace-open action. The local Docker context is resolved without switching the global context; subsequent companion commands pin its Unix-socket endpoint.

Java starts a uniquely named `--rm --init --interactive` container, publishing only `127.0.0.1` on an OS-selected port. It mounts only that workflow’s private workspace and read-only generated configuration. It does not mount the Docker socket, project source, or the user’s home directory. A 2 GiB container limit and 1 GiB JVM maximum heap bound the companion’s memory. Each open workflow can have its own companion.

A private generated CloudBeaver account uses standard local authentication with anonymous access disabled. Java signs in through the Community GraphQL API using its client-side credential encoding. The generated account password and upstream session cookie are not returned to Angular or embedded into the frame URL. A server-side `HttpClient` and cookie jar belong to each workflow. Reverse-proxy header authentication is not enabled.

The existing app-token-protected `/api/projects/{id}/databases` endpoint handles status (GET), asynchronous start (POST with light/dark theme), and close (DELETE). Opening/status grants a separate HttpOnly, SameSite=Strict cookie scoped to `/database/{id}/`. The gateway checks this cookie and the existing Host/Origin boundaries, rejects path traversal, forwards only selected request headers, and removes upstream cookies and authentication headers from browser traffic. It cannot proxy arbitrary destinations: the upstream address comes only from the owned companion’s verified loopback port.

The embedded path permits same-origin framing while Runtime Bay’s main document keeps its original content policy. CloudBeaver uses its supported HTTP event transport when a WebSocket upgrade is unavailable through the JDK HTTP server. Table queries and async results have been verified through this transport. The native bridge remains limited to the trusted main frame.

## Storage and cleanup

Data lives under `~/Library/Application Support/Runtime Bay/databases/{projectId}/`:

- `cloudbeaver.conf`: generated server settings with anonymous access and general forwarding disabled.
- `.cloudbeaver.auto.conf`: a generated companion-account password in Java properties format, mode 0600 inside a 0700 directory. It is preserved across companion restarts.
- `workspace/`: upstream CloudBeaver connections, saved credentials when chosen, scripts, internal H2 metadata, and its logs. Credential protection follows CloudBeaver’s own implementation; Runtime Bay does not claim these files are stored in macOS Keychain.

The container runs as the macOS workspace owner’s numeric UID/GID so it does not change ownership of these host files. Java holds the companion’s stdin open. A watcher inside the container terminates its init process on EOF, including when Java crashes; normal shutdown also closes the stream and waits for exit. Docker removes the owned container and preserves the host workspace directory.

## Validation

Gateway tests cover private cookies, Origin checks, upstream login separation, workflow isolation, traversal rejection, saved account reuse, and close-during-startup behavior. Existing API authentication tests also cover the new routes. Browser verification uses Gobble’s real MySQL connection with its public CA and certificate verification enabled, opens the migration-history table, and executes `SELECT 1` without changing application data. Docker runtime checks confirm loopback binding and cleanup of owned companions.

CloudBeaver’s full UI exposes capabilities beyond this initial integration. Browser uploads, large exports, third-party login flows, and every driver/network combination are not exhaustively covered by Runtime Bay’s tests. The first release focuses on table browsing/editing and SQL for the locally verified MySQL workflow.
