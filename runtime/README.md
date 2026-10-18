# Runtime Bay worker prototype

Experimental RB-S08 contract and local process-lifecycle evidence. This executable
is **not integrated into the application**, bundled, or installed on a remote host.
The current Java/native saved-service supervisor remains active.

Go 1.26.1 is the initial build toolchain. No third-party Go modules are used.
Original source uses the repository's [MIT license](../LICENSE).

```sh
cd runtime
go test -race ./...
go vet ./...
go build -trimpath -o .build/runtimebay-worker ./cmd/runtimebay-worker
```

If a sandbox restricts the normal Go cache, set `GOCACHE` and `GOMODCACHE` to
absolute writable `.build/` directories. Build output must stay under `.build/`.

A worker requires an explicit `--target-id` supplied by its trusted Java parent.
Input is versioned JSON, one request per line; stdout is reserved for responses.
Use a program that maintains both pipes and reads each response before constructing
session-dependent operations. A shell pipeline closes stdin immediately and
therefore shuts down any processes it started.

See [the protocol](../docs/runtime-worker.md) and
[ADR 0001](../docs/decisions/0001-go-worker-ownership.md) before using the prototype.
