# Existing resource attachments (RB-S03)

The workflow Runtime view has **Attached resources → Attach existing**. These are references, not new copies of infrastructure. Java owns definition persistence, discovery metadata, capability checks, and UI navigation. The Go worker prototype remains isolated; this ticket does not install workers or introduce remote service ownership.

| Kind | Stable source | Supported workflow action | Ownership |
| --- | --- | --- | --- |
| Configured service | Source workflow ID + service ID | Inspect the original service and its existing controls | Managed at source; no duplicate service or process slot |
| Database connection | Source Runtime Bay workspace ID + CloudBeaver project ID + connection ID | Open its source database workspace, where the user selects the saved connection | External reference; credentials and SQL remain in CloudBeaver |
| Terminal intent | Saved machine ID | Open a new terminal only on an explicit user action | Saved intent; not a running session |
| Container | Pinned Docker context + endpoint + full container ID | Existing container linking and explicit start/stop/restart controls | External; a shared control affects the same physical container |

## Scope and reconciliation

Service, database, and terminal associations are scoped to the selected environment and stored in `workflow-attachments.json` version 1, with a stable association ID. Rebinding keeps that association ID and requires a reviewed, existing replacement of the same kind. Unlink removes only that association. It does not delete the source definition, close a terminal, stop a companion, or affect other workflows.

These associations complement the existing service/container target graph. Duplicating an environment in the target editor copies service/container target bindings; external associations must be attached separately in the destination environment. Removing an environment with attachments is blocked until they are explicitly unlinked. Host removal or SSH address/user/port changes are blocked while saved terminal intents reference it. Cosmetic host renames are allowed.

Container attachment reuses `workflow-containers.json`: it creates a workflow association to the existing full container ID without Docker commands. This preserves existing behavior across workflow environments; replacements are chosen in **Environments**. Catalog entries deduplicate by pinned physical container identity. Removing one workflow’s container association leaves other associations and the physical container intact. Container ownership is never acquired by linking.

Missing source IDs stay visible, even if a new source has the same display name. Missing references expose only rebind/unlink actions. Closed database workspaces and cached failed host inspections show actionable unavailable states and retain their references. A saved reference is not proof of network reachability. Host status is the last explicit inspection, not an automatic probe; container live status continues through the existing Docker panel. No reference silently falls back to This Mac or a same-named replacement.

## CloudBeaver metadata discovery

Open the source database workspace through the normal Databases UI first, then use **Refresh saved connections** in the picker. Discovery queries an already-running, authenticated companion:

```graphql
query RuntimeBaySavedConnections {
  userConnections { id name projectId }
}
```

The query is verified against [CloudBeaver’s official core schema](https://github.com/dbeaver/cloudbeaver/blob/346400e1cb5161750a4ae7abc2278299ba479ebc/server/bundles/io.cloudbeaver.server/schema/service.core.graphqls). It is a metadata query, not an open/test connection mutation. Runtime Bay persists only source workspace/project/connection IDs, display names, and a metadata refresh timestamp. It does not copy database URLs, passwords, SQL, connection properties, or authentication responses into the attachment registry. Failed refreshes preserve the previous inventory; only a successful refresh can identify a previously saved connection as missing. Opening a reference navigates to its source workspace; it does not auto-select or connect that connection.

## API and persistence

All routes retain app-token, loopback-host and origin checks:

- `GET /api/workflows/{id}/attachments`: selected-environment reference states and supported actions.
- `GET /api/workflows/{id}/attachments/catalog`: saved services, hosts, container links and cached database metadata. No discovery side effects.
- `POST /api/workflows/{id}/attachments/databases/{sourceWorkspaceId}`: explicit metadata refresh from an already-open companion.
- `POST /api/workflows/{id}/attachments`: attach a selected catalog source.
- `PUT /api/workflows/{id}/attachments/{referenceId}`: explicitly rebind an association.
- `DELETE /api/workflows/{id}/attachments/{referenceId}`: unlink an association.
- `POST /api/workflows/{id}/attachments/{referenceId}/resolve`: revalidate source navigation before opening it. Resolve itself opens no process or connection.

Reference mutations/navigation require the expected selected environment and workflow revision. Existing-reference requests also require the expected source key; a stale card cannot open a newly rebound target. API mutations serialize checks with definition changes. The registry is validated, written atomically with user-only permissions, and preserved on malformed/unsupported input. Missing/offline sources never cause automatic deletion.

Tests use disposable source definitions, fake Docker, and a loopback CloudBeaver metadata fixture. They cover shared identity, no launch/duplication on attach, safe metadata selection, failures preserving inventories, missing/same-named replacements, explicit stable-ID rebind, host/environment protection, authentication/origin checks, stale references, and association-only unlinking.
