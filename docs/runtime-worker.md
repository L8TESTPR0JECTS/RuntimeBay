# Experimental runtime worker protocol v1

Status: RB-S08 local prototype, 2026-10-18. No Java transport or remote deployment
is wired to this protocol. [ADR 0001](decisions/0001-go-worker-ownership.md) records
ownership limits, technology boundaries and outstanding acceptance work.

## Transport and trust

Java launches the architecture-matched worker with `--target-id <machine-id>` and
owns its stdin/stdout pipes. A future remote transport runs the exact verified
binary through existing strict OpenSSH; this document does not authorize copying
or installing it. The target ID is a routing guard supplied by the trusted parent,
not attestation of host identity. Verified SSH/binary integrity supply that trust.
There is no network listener or transport authentication token in the prototype.

UTF-8 JSON objects are newline-delimited. Each request contains `version`, `id`,
`operation`, and `targetId`. IDs contain 1–128 bytes. Maximum line length is
64 KiB including its delimiter; responses are bounded to 256 KiB. Unknown fields,
multiple JSON objects on one line, mismatched versions/targets, unknown required
capabilities and missing handshakes receive structured errors. Oversized input,
output failure or a broken input stream closes the session and attempts cleanup.
Diagnostics go to stderr; stdout carries protocol responses only.

A maximum of 4,096 distinct validated operation IDs is accepted per session.
Duplicate IDs are rejected rather than replaying a process start; this prototype
does not cache/replay successful responses. If a response is lost, observe known
handles or terminate the uncertain session before retrying a mutation. It does not
promise exactly-once execution across disconnects or worker restarts.

## Handshake

First send:

```json
{"version":1,"id":"hello-1","targetId":"this-mac","operation":"handshake","requires":["process.start.v1","session.cleanup.v1"]}
```

Example response (session IDs vary):

```json
{"version":1,"id":"hello-1","targetId":"this-mac","sessionId":"<random-session>","result":{"workerVersion":"0.1.0-prototype","os":"darwin","arch":"arm64","capabilities":["process.start.v1","process.observe.v1","process.stop.v1","session.cleanup.v1"],"ownership":"session-scoped; input EOF terminates owned groups; no reconnect adoption"}}
```

All later operations include that exact `sessionId`. Protocol major version must
match exactly. Worker version is independent; new capabilities require explicit
negotiation. Unknown capabilities fail closed. A future additive protocol revision
must negotiate its fields rather than relying on this strict parser to ignore them.

## Start, observe and stop

```json
{"version":1,"id":"start-1","targetId":"this-mac","sessionId":"<random-session>","operation":"process.start","start":{"executable":"/bin/sleep","args":["30"],"directory":"/absolute/project/path"}}
```

The executable and existing working directory must be absolute. Directory symlinks
are resolved. The worker executes argv directly and never inserts an implicit
shell. Explicitly choosing a shell still executes arbitrary user code and must be
reviewed as such. The prototype supplies only a minimal system PATH and locale;
custom environment variables, credential delivery, login-shell setup, readiness
probes and executable prerequisite checks belong to subsequent work.

The result contains a fresh opaque `handle`, `state`, optional `exitCode`,
`events` and `truncated`. A session retains up to 16 handles, including exited ones.
A process slot is never silently reused. Rotate the worker after draining its
owned processes when this experimental limit is reached.

```json
{"version":1,"id":"observe-1","targetId":"this-mac","sessionId":"<random-session>","operation":"process.observe","handle":"<owned-handle>"}
```

`process.stop` uses the same fields with a new ID. Stop sends TERM to the captured
foreground group, allows 300 ms in this prototype, then sends KILL and waits at
most two more seconds. A stop response succeeds only after the owned leader exit
has been observed; timeout is an error, not proof of completion. Stops of an
already-exited owned handle return its final snapshot. Unknown and foreign handles
are rejected, including numeric PIDs passed as handles.

## Output/event schema

```json
{"handle":"<owned-handle>","state":"exited","exitCode":0,"events":[{"sequence":1,"stream":"stdout","text":"hello\n"}],"truncated":false}
```

Each event is a captured stdout/stderr byte chunk with a monotonically increasing
sequence within its process. Chunks are not guaranteed to be lines. Invalid UTF-8
is replaced during JSON serialization. Retention is at most 32 KiB and 256 chunks
per process; evicted output sets `truncated`. Observation returns the retained
snapshot; the caller deduplicates sequence numbers. No unsolicited events are
emitted in this prototype, so the parent must poll. Wall-clock correlation,
readiness/lifecycle event streaming, cursors and durable run history are future
protocol work and are not advertised as available capabilities.

Output can contain application secrets. Bounds do not imply redaction. The worker
never logs request bodies; the Java adapter must apply the project's credential
and context handling policy before retaining/exporting logs or sending them to AI.

## Error envelope

```json
{"version":1,"id":"start-1","targetId":"this-mac","sessionId":"<random-session>","error":{"code":"session_mismatch","message":"operation belongs to a different worker session"}}
```

Codes include `invalid_request`, `incompatible_version`, `target_mismatch`,
`unsupported_capability`, `handshake_required`, `session_mismatch`,
`duplicate_operation`, `session_limit`, `unsupported_operation`, and
`operation_failed`. Diagnostics avoid echoing raw request values. A transport
failure is distinct from an operation error and must surface uncertain ownership.

## Shutdown and validation limits

EOF, invalid/oversized stream termination and normal SIGINT/SIGTERM follow the
same owned-process cleanup path. The prototype does not survive abrupt worker
SIGKILL, app crash with inherited pipes still open, remote reboot or daemonized
children. In particular Unix process group identity across PID reuse remains
unproven; never authorize cleanup using a saved PID/handle after restarting it.

Local tests and cross-builds are evidence for this bounded experiment only. No
SSH transport, remote host, reboot, binary distribution, signing, persistence or
production service migration is exercised here. Complete the ADR's disposable-host
evidence before choosing an operational remote model or closing RB-S08.

## Prototype evidence (2026-10-18)

| Check | Result | Scope |
| --- | --- | --- |
| `go test -race ./...` | Passed | Go 1.26.1, macOS arm64; protocol and lifecycle tests, including an owned foreground descendant and EOF cleanup |
| `go vet ./...` | Passed | Local source and test static checks |
| `CGO_ENABLED=0 go build -trimpath` | Passed | darwin/arm64, darwin/amd64, linux/amd64, linux/arm64 |
| Remote SSH execution, disconnect/reconnect, reboot | Not run | Requires a disposable approved host and an implemented transport |
| OS PID/group reuse, abrupt worker kill recovery | Not established | Blocks production supervision migration |
| Java integration, app bundle signing, provenance validation | Not implemented | RB-S09/RB-S10 and beta packaging follow-up |

Cross-build output is an unsigned local development artifact under `runtime/.build/`.
It must not be copied to hosts or treated as a supported release binary.
