# Integration research

Checked against primary sources on 2026-10-14. Docker container environments are implemented; other integrations remain candidates. Provider policies and APIs must be checked against the versions used during implementation.

## Coding agents

**Codex:** Prefer its documented app-server protocol over scraping terminal output. It offers structured requests/events, threads, and approvals; stdio is documented. Codex supports ChatGPT sign-in for subscription access and API-key sign-in for usage-based access. Preserve the provider's authentication and billing model. An eligible Codex subscription is not a promise of unlimited model usage.

- [Codex app-server](https://developers.openai.com/codex/app-server)
- [Codex authentication](https://developers.openai.com/codex/auth)

**Claude:** A Java adapter can drive the CLI subprocess with structured JSON output, or use a small SDK bridge where the SDK is necessary. Authentication policy needs further verification: Anthropic's Help Center says third-party/SDK use can still draw from subscription limits, while the Agent SDK overview says third parties need prior approval to offer Claude login or rate limits. Runtime Bay should not advertise a universal Claude subscription login while those official documents disagree. API credentials are the clear initial integration path pending resolution.

- [Agent SDK overview and third-party authentication language](https://code.claude.com/docs/en/agent-sdk/overview)
- [Subscription usage update, October 7, 2026](https://support.claude.com/en/articles/15036540-use-the-claude-agent-sdk-with-your-claude-plan)

Coding-agent subscriptions and cloud-hosting accounts are separate provider categories with independent authentication and billing.

**T3 Code:** Its root license is MIT. The repository describes an agent harness control surface; its web package is React, and its architecture includes a separate server/provider layer. Reuse is possible, but it is an application rather than a drop-in Angular/Java widget. Decide between a separately supervised, pinned T3 server adapter and selected reusable provider components after a small compatibility spike. Direct Codex integration supplies a smaller first agent boundary. Retain upstream notices for any copied code and audit the licenses of the exact dependencies shipped.

- [T3 Code repository](https://github.com/pingdotgg/t3code)
- [T3 MIT license](https://github.com/pingdotgg/t3code/blob/main/LICENSE)
- [T3 web package dependencies](https://github.com/pingdotgg/t3code/blob/main/apps/web/package.json)

## API testing

**Bruno:** The repository's root license is MIT, and the project documents a CLI for executing file-based collections. It is a suitable candidate for a local, Git-friendly collection runner. Start with an adapter around a pinned CLI and typed collection results. A shared UI/request editor and collection conversion are additional work; do not claim every Postman collection/script can execute unchanged. Keep enterprise or separately licensed features out of the open-source dependency assumption.

- [Bruno repository and CLI usage](https://github.com/usebruno/bruno)
- [Bruno root MIT license](https://github.com/usebruno/bruno/blob/main/license.md)

**Postman:** Provide optional interoperability through documented collection formats or an installed CLI. Runtime Bay's core should remain usable without Postman. Detect a user-installed Postman CLI or allow users to select its location; never hard-code a development-machine path.

## Desktop packaging

- [Capacitor official platform/environment documentation](https://capacitorjs.com/docs/getting-started/environment-setup): officially supported targets are Android, iOS, and web.
- [Apple WKWebView](https://developer.apple.com/documentation/webkit/wkwebview): embeds web content within a native macOS view.
- [Tauri external binaries](https://v2.tauri.app/develop/sidecar/): a future alternative for hosting a packaged Java engine; target-specific binaries and lifecycle/packaging still need explicit work.
- [Angular compatibility](https://angular.dev/reference/versions): Angular 21 supports Node 24.9 and TypeScript 5.9 used by this first build.
- [JDK HTTP server](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.httpserver/com/sun/net/httpserver/HttpServer.html): the current Java local API server.

## Adapter contract direction

Adapters belong behind Java interfaces and describe their own capabilities: installation detection, authentication state, supported actions, output/result events, cancellation, and version compatibility. Optional executables are detected or selected by the user. An absent tool disables the relevant feature without preventing core service management.

Git should begin with the user's installed Git CLI and structured output, including NUL-delimited filenames. Editor debugger attachment should precede an embedded debugger. Cloud deployment credentials and agent subscription credentials have separate storage and billing semantics. Keep agent approvals visible instead of interpreting arbitrary model text as permission to run an action.

## Portainer and Docker

Portainer's open-source repository uses the zlib license. Its environment-oriented management informed Runtime Bay's host selector, inventory, controls, and inspection workflow. This is original Java/Angular code: Portainer is not embedded, its source is not copied, and its Agent/Edge protocols are not implemented. No Portainer installation is required.

- [Portainer source](https://github.com/portainer/portainer)
- [Portainer license](https://github.com/portainer/portainer/blob/develop/LICENSE)
- [Portainer container workflow](https://docs.portainer.io/user/docker/containers)
- [Docker contexts](https://docs.docker.com/engine/manage-resources/contexts/)
- [Docker SSH/TLS connections](https://docs.docker.com/engine/security/protect-access/)
- [Docker image pulls and registry authentication](https://docs.docker.com/reference/cli/docker/image/pull/)

Docker CLI is an optional user-installed executable that supplies the engine protocol and SSH/TLS transport. Docker Hub is an image registry, not an engine on which containers run. Remote engines need a Docker installation and configured connection.
