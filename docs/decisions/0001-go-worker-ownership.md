# ADR 0001: Java control plane and session-scoped Go worker

- Date: 2026-10-18
- Status: Accepted responsibility boundary; experimental transport/ownership policy
- Story: [RB-S08 / #15](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/15)

## Context

A workflow can span This Mac and multiple remote machines. Java should coordinate
that application graph without maintaining a different process supervisor for
each host. Adding a second general-purpose backend would duplicate product logic.
A small Go executable supplies a common host execution boundary without requiring
a JVM installation on remote machines.

## Responsibilities

| Component | Responsibility |
| --- | --- |
| Java | Workflow/environment definitions, persistence, authentication and authorization, target validation, plans, credential references, adapter coordination and run history |
| Go | Host facts, allowlisted tool discovery, service process identity/lifecycle, readiness observations, bounded output and cleanup on its own machine |
| Angular | Views, user intent, reviews and operational feedback |
| Swift | Native macOS lifecycle, window integration, packaging bootstrap and local worker selection |
| Existing C helper | Interactive PTY terminals; current saved-service supervision until a validated migration |

Docker and CloudBeaver remain independent adapters. The Go worker does not accept
workflow documents, store credentials, make AI requests, select machines, or open
an inbound network listener. Java validates an action before passing its concrete
host-local command to the worker.

## Prototype decision

Start with one parent-owned stdin/stdout worker session. Each worker generates a
fresh random session ID and random handles for processes it starts. Requests name
a target identity supplied at launch, and require a compatible handshake and
matching session before a lifecycle operation. Handles cannot reference arbitrary
PIDs, another worker session, or a process discovered by inspection.

Input EOF and graceful worker termination clean up its foreground process groups.
Restarting a worker never adopts a saved PID or reconnects to a previous handle.
A lost connection is reported as **ownership unknown** by the future Java adapter
until it obtains evidence; it must not be converted into a successful stop.
This prototype cannot observe that ambiguity because no Java adapter exists yet.

The prototype uses Unix process groups and a bounded TERM/KILL escalation. Commands
that daemonize into other sessions are outside its supported foreground contract.
A group-kill implementation has an OS PID/group reuse race after leader exit;
opaque handles prevent arbitrary caller adoption but do not prove OS-level group
identity. **This prototype must not replace the production supervisor until that
race is eliminated or an equivalent native ownership mechanism is demonstrated.**
SIGKILL of the worker itself can bypass cleanup. These limitations are explicit
acceptance work, not claims of reliable supervision.

The current evidence favors ephemeral sessions for a first experiment. Choosing
between ephemeral SSH invocation and an optional persistent remote worker is
**deferred until disposable-host experiments**, including reconnect and reboot.
No persistent worker, service installation, reconnect adoption or remote transfer
is implemented by this decision.

## Initial target/build policy

- Development toolchain: Go 1.26.1, recorded by `runtime/go.mod` and build metadata.
- Candidate beta matrix: darwin/arm64, darwin/amd64, linux/amd64 and linux/arm64.
  A compiled binary is not evidence that the target runtime is supported.
- Local evidence is presently macOS arm64. Other targets require real execution
  and ownership tests, with Go race checks where the target toolchain supports them.
- Future releases pin the compiler and build inputs in CI, use trimmed paths, and
  publish source revision, `go version -m` output, target and SHA-256 provenance.
- Java selects the exact target binary, verifies integrity against trusted release
  metadata, then checks worker/protocol/capabilities. Mismatch disables operations.
- Local worker binaries require normal app signing/packaging checks. Remote worker
  transfer, install, update, persistence and removal require explicit per-host
  review/approval, verified SSH host keys and a private user-owned installation path.
  Host creation or inspection alone must never trigger them.
- An update stops/drains the owned session before replacing its versioned binary.
  Failed updates retain a verified previous binary and must not reconstruct process
  ownership from persisted PIDs. Removal previews the exact owned paths and never
  removes a shared tool installation.
- The standard library is the only Go dependency today. Bundled compiler/runtime
  license/provenance records must be added to packaging before binary distribution.

## Evidence and remaining decision gates

Local tests cover protocol guards, session isolation, bounded logs/requests, exit
status, stop and session-close cleanup. They do not establish production host
identity or isolate an executable from other software running as the same user.
A caller can intentionally run arbitrary code through `process.start`; transport
trust and prior action review are requirements of the Java integration.

RB-S08 remains in progress until a disposable verified-SSH host demonstrates
start/observation/stop, transport loss, reconnection, remote reboot, app shutdown,
worker crash, descendant cleanup and PID/group reuse behavior. Record evidence by
OS/architecture and select the persistence model afterward. RB-S09 owns reproducible
build/bootstrap/preflight integration. RB-S10 owns switching saved-service lifecycle
to the worker. Tool inventory and readiness are specified responsibilities but are
not capabilities of this initial executable.
