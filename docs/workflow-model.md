# Workflow resource model

RB-S01 introduces a versioned definition graph over the existing project and container-link stores. It is a model/API foundation with a read-only **Resource targets** summary in the workflow Runtime view. Environment editing, attachment/rebinding controls, full cockpit layouts, and remote service execution remain later stories.

## Identity and responsibility

| Definition | Identity and meaning |
| --- | --- |
| Workflow | Retains the imported project's ID and filesystem root association; one application can span machines |
| Environment | Stable ID and display name; a migrated workflow begins with one `Local` environment |
| Resource | Retains its service ID or container-link ID, with type, source ID and ownership (`managed` service or `external` container reference) |
| Machine reference | References a machine ID from `hosts.json`; `local` explicitly means This Mac |
| Docker connection | Separate stable ID derived from the pinned context/endpoint pair; optional machine association is explicit, never inferred from an SSH hostname |
| Binding | Stable ID plus environment ID, resource ID and typed target (`machine` or `docker`) |

The default workflow environment is independent of the preferred terminal machine. Every resource has exactly one explicit target in every defined environment. A service binding can reference a registered remote machine or a missing machine ID; it remains visible and cannot run locally as a fallback. A container binding references its existing pinned Docker connection. Model writes cannot change a container endpoint, service command, project root, resource source ID, or ownership type.

The graph stores references, not duplicate command definitions. `projects.json` still owns service commands, directories, ports and imported roots. `workflow-containers.json` still owns full container identities and associations. Saving a graph does not run, inspect, install, start, stop, adopt, or modify any host workload.

## Migration and reconciliation

The Java engine creates `workflows.json` with schema version 1. It validates all legacy inputs before an atomic user-only file write. Source project and container files are never rewritten by migration. Existing IDs and commands remain intact. Environment, binding and Docker connection IDs are deterministically seeded, so rerunning migration produces the same identities.

Each graph read reconciles the existing definitions: new local service definitions receive explicit This Mac bindings, source renames update labels, intentional source deletion removes its graph references, and explicit existing bindings remain pinned. A missing Docker container stays represented as long as its container link exists; live availability remains in the container subsystem. New service definitions created through the legacy controls are local in every existing environment; environment-specific creation/editing policy belongs to RB-S02.

Malformed or unsupported registries, duplicate source IDs, orphan project links and invalid graphs fail before replacement. Error messages identify the preserved registry and recovery path. If migration fails, retain the originals and restore valid source data. If `workflows.json` itself is damaged, stop Runtime Bay, copy it aside, restore a known-good version and reopen. Renaming the graph to allow a fresh migration loses custom graph bindings from that active copy; keep the preserved graph to recover them. Never delete the source registries as a migration remedy.

## API and execution guard

All routes use the existing authenticated loopback API and origin checks:

- `GET /api/workflows`: graph definitions and default-environment resource-target summaries.
- `GET /api/workflows/{projectId}`: one definition and its target summaries.
- `PUT /api/workflows/{projectId}`: replace the definition with validated references; the payload is the GET response's `definition` object. Source identities remain fixed and every environment/resource pair must have a binding.

The API is a low-level foundation for RB-S02; the current interface does not edit environments. A target summary reports `local`, `unsupported` (remote service controls pending), `unresolved` (missing machine), or `linked` (Docker metadata reference). `linked` is not a claim of live health. Docker status remains in the existing container panel.

Graph changes are rejected while any saved service in that workflow is active. Service start/restart revalidates the default-environment binding under the same service operation lock used for definition edits. Only a binding explicitly targeting This Mac can reach the current native service launcher; remote and unresolved bindings receive a conflict response. Stop still uses the captured existing process ownership and never resolves a new target. Changing the terminal preference cannot alter a workflow binding.

## Evidence

Temporary-directory tests cover legacy ID/command/container preservation, repeat migration, atomic user-only persistence, corrupt/unsupported registry preservation, duplicate source rejection, a single environment spanning local/remote machine references, missing-host visibility and preferred-terminal independence. API tests cover authentication/origin checks, model validation, and blocked local execution. Process integration checks reject rebinding during a live run and block a remote-bound start after stop. Fixtures use disposable directories and processes; no personal host or workload is changed.
