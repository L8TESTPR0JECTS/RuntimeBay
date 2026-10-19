# Runtime Bay — proposed product backlog

Created: 2026-10-16  
Status: **Tracked in GitHub Issues and the Runtime Bay Product Roadmap.** The acceptance criteria remain proposals until refined and moved to Ready.

Baseline: [`e1119db`](https://github.com/L8TESTPR0JECTS/RuntimeBay/commit/e1119db) — README and licensing audit complete.

Live tracking: [Runtime Bay — Product Roadmap](https://github.com/orgs/L8TESTPR0JECTS/projects/4) · [GitHub issues](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues) · [Planning ID to issue map](github-issues.md).

## Product intent

Runtime Bay understands an application's operational context and gives the developer a persistent place to operate it. **A workflow is an application, not a host.** One environment of that workflow can combine local processes, containers on Helfer, a database on another machine, and their logs, terminals, and protocol consoles.

AI should help understand existing applications and model new ones. It proposes evidence-backed configuration; the developer reviews concrete changes before they become configuration or runtime actions.

## What already exists

Local service controls and bounded logs/port observations; standalone local/SSH terminal tabs; independent machine records and tool/process inspection; Docker connection and container management; workflow container links; an embedded CloudBeaver database workspace; session SSH passwords; original-color tool logos; appearance, font-size and zoom controls; macOS packaging and third-party notices. These are the starting point, not new backlog stories.

Current limits include local-only saved service execution, no persistent multi-panel workflow cockpit, no shareable workflow manifest, no AI adapter, and no Redis/Kafka protocol consoles. The app does not use a Go worker yet; RB-S08 now has an isolated local protocol/lifecycle prototype and ownership ADR under `runtime/` and `docs/decisions/`. Machine identities and Docker connection identities are currently separate and must remain distinct concepts.

## Technology boundaries for the next runtime stories

The roadmap uses a layered implementation: **Angular** presents workflows; **Java** remains the application control plane for definitions, policy, API, persistence, dependency planning, and adapters; a **Go runtime worker** performs host-local inspection and saved-service process lifecycle on This Mac and supported remote machines; **Swift** owns the macOS shell and OS integration; the existing **C PTY helper** continues to own interactive terminal tabs. Docker and CloudBeaver remain separate integrations. The Go worker is planned work and does not turn Go into a second workflow API or persistence layer.

The worker contract should be versioned and capability-based, with bounded JSON requests/events over a parent-owned local process stream or verified SSH transport. The default design opens no inbound network listener. Remote copy, installation, persistence, update, and removal require explicit per-host approval. Pin Go toolchain and target versions per release, verify worker/protocol compatibility before mutations, and preserve toolchain/source/license provenance in packaged notices. See the [architecture boundary and proposed module layout](../docs/architecture.md#planned-java-and-go-runtime-boundary).

## Proposed delivery slices

| Slice | User-visible outcome | Stories |
| --- | --- | --- |
| **M1 — Connected application cockpit** | Model a distributed application, bind existing resources across machines, save/share the definition, and restore its useful views without executing anything automatically. | RB-S01–RB-S07 |
| **M2 — Reliable distributed runs** | Validate remote locations, operate owned remote services, coordinate dependencies, and show run history and protected targets. | RB-S08–RB-S13 |
| **M3 — AI-assisted setup** | Connect one provider, discover existing setups, review changes, and draft a new application's operational blueprint. | RB-S14–RB-S17 |
| **Later — Protocols, diagnosis, portability** | Add Redis/Kafka consoles, cross-resource timelines, and a portable tool inventory. | RB-S18–RB-S21 |
| **Beta gate** | Validate and distribute the agreed supported feature set. AI/protocol stories are not automatically prerequisites for a distributed-runtime beta. | RB-S22 |

This is a proposed sequence, not release dates or sprint commitments. Start RB-S01 and the RB-S08 design spike first; refine RB-S02/RB-S06 while the remote lifecycle decision is being tested. RB-S07 enables an early RB-S14 provider spike alongside runtime work; AI proposal application still depends on a stable manifest/review boundary. Estimate after refinement, rather than assigning speculative story points.

**First end-to-end demonstration:** open Gobble, select an environment that spans This Mac and Helfer, see its local services and linked remote dependencies in a saved cockpit, inspect a failed/offline resource without losing the rest of the view, and export the definition without secrets. M2 adds verified remote service ownership and lifecycle controls to that same application model.

## Planning conventions

- `RB-E##` and `RB-S##` are stable repository planning IDs, not GitHub issue numbers. Use [github-issues.md](github-issues.md) to find each live issue. All items remain proposed and unassigned until refined.
- High/Medium/Low express relative priority, not a committed delivery date. Dependencies identify implementation prerequisites, not just reading order.
- Each story needs a demonstration against its acceptance criteria, appropriate automated checks for changed runtime/security behavior, updated documentation, and dependency/license notices when adding third-party tooling.
- Failure states, target identity, bounded output, accessibility, and theme behavior are part of done where relevant. An unavailable target must never cause a fallback mutation on another machine.
- The owner reviews the plan before sprint selection. Provider choice, remote ownership design, and the beta support matrix are explicit decisions, not hidden implementation assumptions.

## Epics

| Planning ID | Epic | Outcome |
| --- | --- | --- |
| [RB-E01](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/1) | Application model and shared definitions | Model one application with resources distributed across hosts, and share its intended setup without secrets. |
| [RB-E02](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/2) | Persistent workflow cockpit | Bring an application’s runtime views together and restore useful context without replaying execution. |
| [RB-E03](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/3) | Distributed runtime operations | Start, observe, and stop the correct owned runtime on the correct machine, with explicit failure handling. |
| [RB-E04](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/4) | Credentials and environment protection | Keep credentials outside shared definitions and make the effective target clear before mutations. |
| [RB-E05](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/5) | AI-assisted application setup | Discover existing application setups and propose new ones through a reviewable, provider-independent flow. |
| [RB-E06](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/6) | Protocol consoles and troubleshooting | Give data services purposeful tools and correlate operational events across the application. |
| [RB-E07](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/7) | Environment portability and beta delivery | Capture reproducible tooling requirements and prepare a tested macOS beta distribution. |

## Story index

| ID | Story | Epic | Priority | Slice |
| --- | --- | --- | --- | --- |
| [RB-S01](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/8) | Introduce a workflow resource model | RB-E01 | High | M1 |
| [RB-S02](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/9) | Bind resources per environment | RB-E01 | High | M1 |
| [RB-S03](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/10) | Attach and reconcile existing application resources | RB-E01 | High | M1 |
| [RB-S04](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/11) | Build a multi-host workflow cockpit | RB-E02 | High | M1 |
| [RB-S05](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/12) | Restore workflow layout and operational context | RB-E02 | High | M1 |
| [RB-S06](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/13) | Import and export a shareable workflow definition | RB-E01 | High | M1 |
| [RB-S07](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/14) | Manage credential references separately from workflows | RB-E04 | High | M1 |
| [RB-S08](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/15) | Define the Go runtime worker and ownership contract | RB-E03 | High | M2 |
| [RB-S09](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/16) | Build and verify host workers and runtime prerequisites | RB-E03 | High | M2 |
| [RB-S10](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/17) | Supervise saved services through the Go worker | RB-E03 | High | M2 |
| [RB-S11](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/18) | Run dependency-aware plans across hosts | RB-E03 | High | M2 |
| [RB-S12](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/19) | Record run history and reconnect diagnostics | RB-E03 | Medium | M2 |
| [RB-S13](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/20) | Make environment and target boundaries visible | RB-E04 | High | M2 |
| [RB-S14](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/21) | Add one AI provider through an adapter | RB-E05 | High | M3 |
| [RB-S15](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/22) | Discover an existing application with AI assistance | RB-E05 | High | M3 |
| [RB-S16](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/23) | Review and apply AI-proposed workflow changes | RB-E05 | High | M3 |
| [RB-S17](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/24) | Model a new application from a reviewed blueprint | RB-E05 | Medium | M3 |
| [RB-S18](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/25) | Provide a workflow-aware Redis console | RB-E06 | Medium | Later |
| [RB-S19](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/26) | Provide a workflow-aware Kafka console | RB-E06 | Medium | Later |
| [RB-S20](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/27) | Correlate runtime events across workflow resources | RB-E06 | Medium | Later |
| [RB-S21](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/28) | Export a portable development-tool inventory | RB-E07 | Low | Later |
| [RB-S22](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/29) | Validate and package the first distributed-workflow beta | RB-E07 | High | Beta gate |

## Proposed stories

<a id="rb-s01"></a>

### RB-S01 — Introduce a workflow resource model

**Epic:** RB-E01 · **Priority:** High · **Slice:** M1 · **Status:** In review — workflow model foundation

**Depends on:** None

As a developer, I want a workflow to describe my application and its resources independently of a single host, so I can model how the application actually runs.

Acceptance criteria:

1. Represent a workflow, environment, resource, host reference, and resource binding with stable IDs; distinguish a machine from a Docker connection.
2. A single workflow/environment can bind services and dependencies to at least two machines; changing the preferred terminal host does not retarget a saved resource.
3. Migrate existing projects, service IDs, and container links without losing commands or associations; failures preserve the original files and explain recovery.
4. Keep imported project roots and current local service behavior working; unresolved machine references remain visible rather than silently falling back to This Mac.

<a id="rb-s02"></a>

### RB-S02 — Bind resources per environment

**Epic:** RB-E01 · **Priority:** High · **Slice:** M1 · **Status:** Implemented; in review
**Depends on:** RB-S01

As a developer, I want Local, Staging, or Production configurations to map each resource to its own target, so an environment can span multiple machines.

Acceptance criteria:

1. Create, rename, select, and remove environment configurations without creating or deleting the actual remote resources.
2. In one Gobble environment, bind a service to This Mac, a database to Helfer, and Kafka to another saved target; persist and reopen those bindings.
3. Changing environment shows the effective target and configuration changes before execution and preserves other environments’ mappings.
4. Removing an in-use host or Docker connection is blocked or requires explicit resolution of affected bindings; bindings are never silently reassigned.

**Implementation:** Environments editor with reviewed changes, independent machine/container bindings, pinned container controls, optimistic revisions, and in-use target guards. Database dependencies currently bind as saved database containers; native CloudBeaver connection resources remain attachment/reconciliation work. Service commands/directories/ports stay shared; remote service execution remains gated on RB-S08. See [workflow model](../docs/workflow-model.md).

<a id="rb-s03"></a>

### RB-S03 — Attach and reconcile existing application resources

**Epic:** RB-E01 · **Priority:** High · **Slice:** M1 · **Status:** Proposed  
**Depends on:** RB-S01, RB-S02

As a developer, I want to attach resources that already exist, so Runtime Bay adapts to my application without rebuilding its infrastructure.

Acceptance criteria:

1. Link existing container references, configured services, database workspace connections, and host terminal intents to a workflow using stable identities; retain the existing container linking behavior.
2. Distinguish an external resource from an app-owned runtime and show which actions are actually supported; attaching alone does not create, restart, or take ownership of it.
3. If a host is offline or a container is recreated, retain the reference with an actionable unavailable/missing state and require explicit rebinding to a replacement.
4. Unlinking removes only the workflow association; sharing one dependency across two workflows neither duplicates it nor implies exclusive lifecycle ownership.

<a id="rb-s04"></a>

### RB-S04 — Build a multi-host workflow cockpit

**Epic:** RB-E02 · **Priority:** High · **Slice:** M1 · **Status:** Proposed  
**Depends on:** RB-S02, RB-S03

As a developer, I want services, dependencies, logs, and consoles in one workflow view, so I can operate the application without navigating between disconnected dashboards.

Acceptance criteria:

1. Show resource cards/panels with their workflow, environment, machine or Docker target, current state, and supported actions.
2. Support a Gobble layout with local service logs, Helfer container status, its database workspace, and a Helfer terminal entry; keep general-purpose standalone terminal tabs available.
3. Allow opening, closing, resizing, and rearranging supported panels; isolate a failed or unavailable resource so the remaining cockpit is usable.
4. Display only implemented capabilities: protocol consoles and remote service controls appear as available only after their corresponding stories ship.

<a id="rb-s05"></a>

### RB-S05 — Restore workflow layout and operational context

**Epic:** RB-E02 · **Priority:** High · **Slice:** M1 · **Status:** Proposed  
**Depends on:** RB-S04

As a developer, I want my workflow to reopen with its useful context, so I can resume work without reconstructing the dashboard.

Acceptance criteria:

1. Persist panel arrangement, resource selection, names, filters, selected environment, and terminal working-directory intents by workflow/environment.
2. After an app restart, restore the saved layout and references while showing exited, disconnected, or unavailable runtime states accurately.
3. Restoration never executes a shell command, starts a service, submits SQL, recreates an active transaction, or replays terminal input automatically.
4. Restore database navigation/editor state only where the upstream interface supports it; mark unsupported or lost unsaved state explicitly. Corrupt layout data falls back safely without discarding resource definitions.

<a id="rb-s06"></a>

### RB-S06 — Import and export a shareable workflow definition

**Epic:** RB-E01 · **Priority:** High · **Slice:** M1 · **Status:** Proposed  
**Depends on:** RB-S01, RB-S02

As a contributor, I want a versioned workflow definition in my repository, so teammates can recover the intended application setup without copying private machine configuration.

Acceptance criteria:

1. Define and document a versioned manifest format with logical resources, environment bindings, relative project paths, dependency references, and optional layout intentions.
2. Export logical host aliases and credential references rather than passwords, tokens, private keys, private connection strings, or user-specific absolute paths; flag nonportable values for review.
3. Import validates the schema, previews additions/changes and alias-to-host mappings, and leaves existing data unchanged if validation or application fails.
4. Round-trip an example multi-host Gobble definition; unsupported versions report a useful error. Importing does not execute commands or apply infrastructure changes.

<a id="rb-s07"></a>

### RB-S07 — Manage credential references separately from workflows

**Epic:** RB-E04 · **Priority:** High · **Slice:** M1 · **Status:** Proposed  
**Depends on:** RB-S01

As a developer, I want credentials kept outside portable workflow definitions, so I can share setup safely and choose how long credentials are retained.

Acceptance criteria:

1. Introduce typed credential references for supported connections; keep the existing session-only SSH password option and add an explicit opt-in macOS Keychain storage option.
2. Mask secret fields; never return stored secret values through ordinary read/list APIs or include them in logs, exports, generated command arguments, or AI requests.
3. Support replacement, forgetting, missing credentials, and denied/unavailable Keychain access with clear UI states; forgetting does not claim to revoke an already-authenticated SSH connection.
4. Keep SSH host-key verification and target/account scoping intact; cover persistence permissions, secret redaction, and credential removal with meaningful checks.

<a id="rb-s08"></a>

### RB-S08 — Define the Go runtime worker and ownership contract

**Epic:** RB-E03 · **Priority:** High · **Slice:** M2 · **Status:** In progress — local prototype; remote ownership evidence pending

**Depends on:** None

As a maintainer, I want a versioned Go worker contract shared by local and remote hosts, so Java can coordinate reliable host inspection and service lifecycle without becoming the process supervisor.

Acceptance criteria:

1. Record an architecture decision that assigns workflow definitions, authorization, persistence, dependency plans, and integrations to Java; host facts, tool inventory, process identity, readiness, bounded output, and child cleanup to Go; UI to Angular; macOS lifecycle to Swift; and interactive PTYs to the existing native helper.
2. Define a versioned capability handshake and JSON request/event schema over parent-owned stdin/stdout; diagnostics use stderr, payloads and output are bounded, protocol versions are checked before mutation, and the worker exposes no inbound listener by default.
3. Prototype local and verified-SSH invocation on This Mac and disposable supported hosts; test start, observation, disconnect/reconnect, stop, PID reuse, remote reboot, and app shutdown before choosing short-lived SSH invocation or optional persistent worker mode.
4. Select the initial OS/architecture matrix, Go toolchain versioning, binary integrity/provenance, cleanup/update path, and compatibility policy. Any remote worker copy, installation, update, persistence, or removal requires explicit approval for that host; host creation alone never installs or runs it.

<a id="rb-s09"></a>

### RB-S09 — Build and verify host workers and runtime prerequisites

**Epic:** RB-E03 · **Priority:** High · **Slice:** M2 · **Status:** Proposed  
**Depends on:** RB-S02, RB-S08

As a developer, I want a compatible Go worker to report each host’s tools and validate service locations, so preflight examines the target machine without guessing from my Mac.

Acceptance criteria:

1. Build and bundle a pinned, architecture-matched Go worker for This Mac and the OS/architectures selected in RB-S08; record toolchain version and binary provenance and run the Go test/static-check suite in packaging CI.
2. Have the worker report OS, architecture, worker/protocol versions, capabilities, and an allowlisted tool inventory with paths and bounded versions; reject incompatible workers visibly and never silently target a different machine.
3. Configure a remote project root and resource-relative working directory per host/environment, then validate existence, access, required executables, and version requirements on that selected host without installing application dependencies.
4. Preview the effective host, worker version, directory, command, and redacted configuration before launch. Remote worker deployment itself requires explicit approval. Do not assume local paths exist remotely, sync source implicitly, or execute project configuration during preflight.

<a id="rb-s10"></a>

### RB-S10 — Supervise saved services through the Go worker

**Epic:** RB-E03 · **Priority:** High · **Slice:** M2 · **Status:** Proposed  
**Depends on:** RB-S07, RB-S08, RB-S09

As a developer, I want saved services on This Mac and remote hosts to use the same Go-owned lifecycle contract, so Runtime Bay can reliably inspect, reconnect to, and stop only processes it started.

Acceptance criteria:

1. Route local saved-service start/observe/restart/stop through the bundled Go worker and remote operations through the matching verified worker; Java remains the policy/orchestration API and the worker is not a second source of workflow configuration.
2. Give each run a collision-resistant identity tied to its target host, service, and start instance; the Go worker owns descendants and emits bounded, ordered output and lifecycle events using the RB-S08 protocol.
3. Reconnect and reconcile unknown/disconnected state after SSH or app interruption without trusting a bare PID; refuse stop/restart unless the worker verifies ownership. Preserve external/shared dependency protection and existing user commands.
4. Make the remote persistence/disconnect/app-close behavior visible before launch. Verify cancellation, host reboot, worker upgrade/cleanup, PID reuse, and app shutdown on disposable fixtures. Any required remote worker installation must follow explicit per-host approval.

<a id="rb-s11"></a>

### RB-S11 — Run dependency-aware plans across hosts

**Epic:** RB-E03 · **Priority:** High · **Slice:** M2 · **Status:** Proposed  
**Depends on:** RB-S03, RB-S10

As a developer, I want Java to coordinate a dependency-aware plan across Go workers, so services start after their required dependencies are usable on the correct hosts.

Acceptance criteria:

1. Define dependency edges and supported readiness checks; Java rejects cycles and shows the ordered plan, worker versions, targets, and affected resources before starting.
2. Coordinate versioned readiness events from the selected Go workers rather than treating an open port alone as application health; timeouts and failed dependencies block dependent starts with useful diagnostics.
3. Cancel a plan without indiscriminately stopping existing resources; distinguish resources started by this run from shared or pre-existing dependencies.
4. A stop plan identifies ownership and shared dependencies, previews its effects, and never stops an external/shared resource merely because the workflow references it.

<a id="rb-s12"></a>

### RB-S12 — Record run history and reconnect diagnostics

**Epic:** RB-E03 · **Priority:** Medium · **Slice:** M2 · **Status:** Proposed  
**Depends on:** RB-S10

As a developer, I want a bounded history of workflow runs, so I can understand what happened after leaving a view or reconnecting.

Acceptance criteria:

1. Record run ID, resource identity, environment/target, configuration revision, timestamps, exit/result state, and operation failures.
2. Persist metadata separately from live terminal buffers and active transactions; define a bounded retention policy and user-visible cleanup behavior.
3. Redact known secret fields and sensitive configuration; raw terminal transcripts and complete environment dumps are not persisted by default.
4. After restart, show historical outcomes separately from observed current state; interrupted or unreachable runs are not falsely reported as successful or stopped.

<a id="rb-s13"></a>

### RB-S13 — Make environment and target boundaries visible

**Epic:** RB-E04 · **Priority:** High · **Slice:** M2 · **Status:** Proposed  
**Depends on:** RB-S02, RB-S07

As a developer, I want to recognize the effective environment and host before changing a runtime, so I avoid operating on the wrong application target.

Acceptance criteria:

1. Allow marking an environment as protected and show its name/target consistently in panels, terminals, and mutation dialogs; do not rely on color alone.
2. Protected-environment start/stop/restart, container removal, and other supported mutations present the exact resource, target, and effect before execution.
3. Capture and revalidate the selected binding at execution time; if the target changes while a dialog is open, require a fresh review rather than executing against the new target.
4. Keep database transaction controls explicit and document enforcement limits inside the embedded CloudBeaver UI; do not claim a read-only guarantee unless enforced at the connection/backend boundary.

<a id="rb-s14"></a>

### RB-S14 — Add one AI provider through an adapter

**Epic:** RB-E05 · **Priority:** High · **Slice:** M3 · **Status:** Proposed  
**Depends on:** RB-S07

As a developer, I want to connect an AI provider with a clear credential and data boundary, so I can use assisted tooling without coupling Runtime Bay to one vendor.

Acceptance criteria:

1. Choose and document one initial provider and its supported authentication method; do not assume a consumer chat subscription grants API access.
2. Keep the adapter contract separate from workflow orchestration and provider-specific UI; use credential references rather than embedding keys in workflow files.
3. Before sending context, show the destination/provider and selected files or runtime information; exclude secrets and unselected context by default.
4. Support cancellation, connection/authentication errors, and provider limits without blocking ordinary app use; expose usage/cost information only when actually available.

<a id="rb-s15"></a>

### RB-S15 — Discover an existing application with AI assistance

**Epic:** RB-E05 · **Priority:** High · **Slice:** M3 · **Status:** Proposed  
**Depends on:** RB-S06, RB-S14

As a developer, I want Runtime Bay to analyze selected project configuration, so it proposes a workflow that matches how my application is already set up.

Acceptance criteria:

1. Select the inputs to analyze, such as supported IntelliJ run configuration files, package scripts, Maven/Gradle configuration, Compose files, and existing Runtime Bay definitions.
2. Produce a proposed resource graph, commands, directories, dependency suggestions, and unresolved host mappings with evidence pointing to the source file/setting.
3. Mark confidence and ambiguity; treat repository files, logs, and comments as data rather than authority to execute instructions.
4. Discovery does not start processes, install software, modify files, deploy resources, or transmit unselected content. Redact credentials before provider submission.

<a id="rb-s16"></a>

### RB-S16 — Review and apply AI-proposed workflow changes

**Epic:** RB-E05 · **Priority:** High · **Slice:** M3 · **Status:** Proposed  
**Depends on:** RB-S15

As a developer, I want to review the concrete changes proposed by AI, so I control which suggestions become configuration.

Acceptance criteria:

1. Show a structured before/after diff for each proposed resource, command, dependency, directory, environment binding, and target; make assumptions and unresolved values visible.
2. Allow accepting, editing, or rejecting individual changes; run the same validation used by manual configuration and block unresolved required mappings.
3. Apply accepted changes atomically to workflow configuration, with a recoverable prior version and clear failure handling.
4. Applying configuration does not execute its commands or install/deploy infrastructure; execution remains a separate reviewed action under the normal target/ownership rules.

<a id="rb-s17"></a>

### RB-S17 — Model a new application from a reviewed blueprint

**Epic:** RB-E05 · **Priority:** Medium · **Slice:** M3 · **Status:** Proposed  
**Depends on:** RB-S06, RB-S14, RB-S16

As a developer, I want to describe an application I am starting, so Runtime Bay helps structure its operational setup before the services exist.

Acceptance criteria:

1. Collect the intended service types, data dependencies, environments, host choices, and runtime/version requirements without inventing available infrastructure.
2. Generate an editable proposed graph and shareable workflow definition, identifying resources that exist, need provisioning, or still require a decision.
3. Validate duplicate names, dependency cycles, required mappings, and supported adapter capabilities; unsupported integrations remain labeled as proposals.
4. Use the same review/apply flow as existing-project discovery; any file generation or provisioning is a separately previewed action, never an implicit consequence of describing the application.

<a id="rb-s18"></a>

### RB-S18 — Provide a workflow-aware Redis console

**Epic:** RB-E06 · **Priority:** Medium · **Slice:** Later · **Status:** Proposed  
**Depends on:** RB-S03, RB-S04, RB-S07

As a developer, I want a Redis-specific view linked to my application, so I can inspect data without reconstructing connection context in a raw terminal.

Acceptance criteria:

1. Bind a supported Redis connection to a workflow/environment with explicit endpoint, authentication/TLS options, and target labeling.
2. Browse keys using bounded incremental scans and inspect supported value types without blocking the UI or loading the entire keyspace.
3. Separate read operations from explicit writes/deletes; show affected key and target for mutations, honoring protected-environment rules when that capability is present.
4. Handle unavailable connections and large/binary values clearly; preserve connection/view references without retaining credentials or automatically replaying commands.

<a id="rb-s19"></a>

### RB-S19 — Provide a workflow-aware Kafka console

**Epic:** RB-E06 · **Priority:** Medium · **Slice:** Later · **Status:** Proposed  
**Depends on:** RB-S03, RB-S04, RB-S07

As a developer, I want a Kafka-specific view for my application, so I can inspect topics and bounded message activity in context.

Acceptance criteria:

1. Configure and bind a supported cluster connection, including authentication/TLS references, independently of whether brokers run in Docker.
2. List topics/partitions and inspect a bounded sample from an explicitly selected topic, partition, and offset; show truncation and refresh behavior.
3. Inspection must not commit offsets for an application consumer group or alter its consumer state.
4. Producing messages or changing topics, if supported, is a separate explicit mutation with target review; unsupported operations are not presented as working controls.

<a id="rb-s20"></a>

### RB-S20 — Correlate runtime events across workflow resources

**Epic:** RB-E06 · **Priority:** Medium · **Slice:** Later · **Status:** Proposed  
**Depends on:** RB-S11, RB-S12

As a developer, I want a common timeline across services and dependencies, so I can trace failures without jumping between isolated logs.

Acceptance criteria:

1. Combine timestamped supported lifecycle/readiness events with resource, run, environment, and target identifiers in a filterable bounded timeline.
2. Flag missing observations and clock skew across hosts instead of implying precise ordering where it is not supported.
3. A dependency outage and subsequent service failure can be viewed together; inferred relationships are labeled as hypotheses rather than asserted causes.
4. Redact secrets and honor retention/export choices; richer Redis/Kafka events appear only once those adapters exist.

<a id="rb-s21"></a>

### RB-S21 — Export a portable development-tool inventory

**Epic:** RB-E07 · **Priority:** Low · **Slice:** Later · **Status:** Proposed  
**Depends on:** RB-S06

As a developer moving to a new Mac, I want a portable record of the tools my workflows require, so I can review what the new machine is missing.

Acceptance criteria:

1. Export discovered active tools/versions and distinguish observed installation facts from workflow-declared requirements; unknown installation sources remain unknown.
2. Exclude credentials and machine-specific private paths by default; support logical tool/version-manager references in a documented versioned format.
3. On another Mac, compare the manifest with an inspection and show satisfied, missing, incompatible, and unresolved requirements, including CPU architecture differences.
4. This story does not install or upgrade software. Automated restoration and a companion CLI remain separate future stories after the inventory format is validated.

<a id="rb-s22"></a>

### RB-S22 — Validate and package the first distributed-workflow beta

**Epic:** RB-E07 · **Priority:** High · **Slice:** Beta gate · **Status:** Proposed  
**Depends on:** RB-S05, RB-S06, RB-S07, RB-S10, RB-S12, RB-S13

As a beta user, I want an installable macOS build with clear capabilities and dependable recovery, so I can try Runtime Bay with my own application.

Acceptance criteria:

1. Exercise the agreed beta support matrix: local Mac plus a disposable supported SSH host, multi-host workflow restore, missing credentials, host disconnect, and safe ownership checks.
2. Build, test, and package Go workers for the supported matrix; verify matching worker/protocol versions, binary integrity, child cleanup, and documented recovery for an unavailable or rejected worker.
3. Pass relevant Java, Go, API, native, and frontend checks; verify fresh install, supported-data migration, cancellation, package signatures, bundled third-party/Go notices, and documented recovery steps.
4. Publish a capability/limitations matrix that distinguishes implemented features from planned AI, protocol, deployment, and environment-restoration features.
5. Before a public binary release, configure the release owner’s Developer ID/notarization and artifact/source provenance. Signing credentials are supplied through the release process, not stored in Git.

## Decisions to refine before implementation

1. **Workflow vocabulary and manifest:** choose the concrete schema/file name and migration approach in RB-S01/RB-S06; keep project folders distinct from distributed workflow definitions.
2. **Go worker contract and remote process ownership:** complete RB-S08 before promising reliable stop/reconnect behavior. Validate the Go worker protocol, supported OS/architecture matrix, remote installation/update consent, and shutdown policy on evidence.
3. **Credential retention:** session-only remains supported; Keychain persistence is opt-in, with credential references excluded from AI submissions and secret values excluded from shared manifests.
4. **First AI provider:** select one supported adapter/authentication path in RB-S14; a coding-agent integration and a workflow-analysis provider are not automatically the same feature.
5. **Database interface boundary:** agree which CloudBeaver state can be restored and which protections require upstream support or database-level permissions.
6. **Beta scope and release owner:** agree the runtime/host matrix and public-signing process. Do not make every future protocol or AI feature a beta gate by default.

## Optional Jira import

[backlog.csv](backlog.csv) is a UTF-8 planning snapshot containing **7 epics and 22 stories**. GitHub is the active tracker; see [github-issues.md](github-issues.md) for live issue links. This Markdown file is the canonical proposal; regenerate the CSV snapshot when changing the proposal before import. The CSV includes story text and acceptance criteria, rather than summaries alone.

If the project later adopts Jira, map `Issue Type`, `Summary`, `Description`, `Priority`, and `Labels` to fields available in that Jira project. `Backlog ID`, `Epic Reference`, `Depends On`, and `Milestone` are planning metadata; map them to suitable custom fields or retain the copies in each story description. They do not establish Jira parent links or blockers automatically. Create/import the epics first, obtain their Jira keys, then map story parents and dependency links through the chosen project's supported import process. Check a small import before importing the complete snapshot. No credentials or private connection data are included.

## Deferred areas requiring separate refinement

Git/worktrees; coding-agent sessions and approvals; API collections and request execution; editor debugger integration; deployments and deployment history; Compose/Kubernetes/Swarm operations; automated environment installation and a restoration CLI; additional AI providers. These remain part of the product direction, but this proposal does not disguise them as already implemented or attach unreviewed scope to the first slices.
