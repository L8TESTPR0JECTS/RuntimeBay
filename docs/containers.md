# Containers & Hosts

Runtime Bay provides a Portainer-inspired workflow for Docker standalone engines, implemented in Java and Angular. No Portainer server or agent is required.

## Local engines

Install Docker CLI and start a local engine such as Docker Desktop or Colima. Open **Hosts** to discover environments, or **Containers** to view workloads. Existing contexts appear, with the current local context preferred. Remote contexts connect when selected. Docker is detected on PATH, in common Homebrew locations, and inside the Docker Desktop bundle. Development can override the executable with `RUNTIMEBAY_DOCKER`.

Missing Docker disables the container workspace while normal project services continue to work.

## Remote machines

The host needs a Docker engine and an SSH user with permission to access its Docker socket. Verify SSH authentication and the host key using your normal Terminal workflow first. Runtime Bay preserves those checks.

On **Hosts**, use **Remote host**, give it a name such as `dev-server`, and enter `ssh://developer@dev.example.com:22`. This adds Docker context metadata on your Mac. It does not install a remote agent, expose a daemon port, or change your terminal's active context. An optional socket path can target rootless Docker, for example `ssh://developer@dev.example.com/run/user/1000/docker.sock`.

You can also configure and authenticate a context externally:

```sh
docker context create dev-server --docker host=ssh://developer@dev.example.com
docker --context dev-server info
```

Refresh **Hosts** and use **View containers**, or choose it in the **Containers** host selector. Every request targets that explicit context. If its address changes, refresh and select it again.

Existing TCP contexts require verified mutual TLS with a trusted CA, client certificate, and client key. Unverified or unauthenticated TCP contexts are unsupported. See [Docker's SSH/TLS documentation](https://docs.docker.com/engine/security/protect-access/).

## Containers

- Filter all containers by name, image, state, Compose project, or linked workspace project.
- Start, stop, restart, pause, or resume on the selected host.
- Inspect recent stdout/stderr, CPU/memory/network usage, exit status, restart policy, networks, and mount destinations.
- Create a stopped container from a cached image, with optional project linkage, TCP publishing, literal command arguments, environment values, and named-volume mounting.
- Remove a stopped container after confirming its host. No force removal or volume deletion is performed.

Published TCP ports bind to `127.0.0.1` on the selected engine's host. For a remote workload published on port 8080, you can forward it to your Mac:

```sh
ssh -L 18080:127.0.0.1:8080 developer@dev.example.com
```

Open `http://127.0.0.1:18080` while the tunnel runs. The app does not automatically expose workloads on all host interfaces.

Containers keep running after Runtime Bay closes. Normal project shell services still use the supervised shutdown behavior. A timed-out mutation can have an uncertain outcome; refresh the host before retrying.

## Images, registries and storage

**Images & registries** shows images cached on the host. **Pull image** accepts a Docker Hub reference, registry path, or digest. Progress, errors, completion and cancellation appear in the workspace. Active pull status can be recovered after changing pages/reloading. Up to 65,536 output characters are retained per operation.

Private pulls use Docker's existing credentials and credential helper. Sign in with `docker login <registry>` through your usual flow. Runtime Bay does not collect registry passwords or duplicate SSH keys/certificates.

**Volumes & networks** lists storage and networks. Named volumes can be mounted during container creation. Volume/network deletion, pruning, registry publishing/catalog search, interactive exec, Compose deployment, Kubernetes, Swarm, and Portainer Agents are outside this version.

Creation environment values are sent through a temporary file with mode 0600 and removed afterward. Docker stores the effective container configuration. Runtime Bay does not persist those values or display the complete environment in its inspector.

## Validation

```sh
npm run check
```

The deterministic suite covers local/SSH/TLS discovery, endpoint changes, arguments, secret isolation, API authentication, removal constraints, and pulls/cancellation. Remote tests use fixtures and do not contact a remote host.

An opt-in real-engine test creates a uniquely named disposable container and operates only on its returned ID. It expects `alpine:latest` in the cache and requires a local context:

```sh
RUNTIMEBAY_LIVE_DOCKER_CONTEXT=desktop-linux npm run check
```

To also test a real Docker Hub pull of the official Alpine image:

```sh
RUNTIMEBAY_LIVE_DOCKER_CONTEXT=desktop-linux RUNTIMEBAY_LIVE_DOCKER_PULL=1 npm run check
```

The container fixture is removed in cleanup. Downloaded images remain cached. No live remote host is required or modified by these tests.
