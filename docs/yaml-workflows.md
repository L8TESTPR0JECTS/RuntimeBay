# Repository workflow configuration

Runtime Bay supports `runtimebay.yaml` in an imported project root. It describes local processes, one-off CLI tasks, environments, dependencies, and references to resources configured in Runtime Bay. It is a first local infrastructure-as-code surface; it does not configure global appearance, install tools, deploy a Go worker, or implement distributed process execution.

## Use it

1. Open a workflow → **YAML configuration**. Load the repository file, export current definitions, or paste YAML.
2. **Validate YAML**, map logical hosts and resource aliases to saved objects, then **Preview import**.
3. Review additions, replacements, removals, and environment changes → **Apply definitions**. All existing workflow processes must be stopped. Import never executes commands.
4. **Create/Save runtimebay.yaml** writes the editor text into that project. Saving neither imports nor executes it. External file edits require reloading before saving.
5. **Review stack run** (or a YAML service’s **Review run**) shows the imported commands and their dependency order for the selected environment. **Execute reviewed plan** starts them. Editing the file alone does not change the imported definition.

[Local-stack example](../examples/local-stack/runtimebay.yaml) pairs with [Compose](../examples/local-stack/compose.yaml). Copy/adapt both into an application that has `npm run dev`, and install Docker/Compose, Node/npm, and optionally AWS CLI yourself. Docker/AWS commands use the user's existing CLI configuration and permissions. The example is never executed by import. Its AWS identity task is manual and requires `DEV_AWS_PROFILE` in the backend app’s launch environment; Finder-launched apps may not inherit shell exports. Existing AWS shared profiles/credentials also work through normal CLI resolution.

## Version 1 format

```yaml
apiVersion: runtimebay.dev/v1
kind: Workflow
name: My application
hosts: [local, worker]
defaultEnvironment: development
processes:
  prepare:
    command: printf ready
    mode: task
  api:
    command: npm run dev
    directory: .
    port: 3000
    dependsOn: [prepare]
    env:
      NODE_ENV: development
      API_TOKEN: '${env:DEV_API_TOKEN}'
environments:
  development:
    name: Development
    bindings: {api: local}
  remoteIntent:
    bindings: {api: worker}
```

- `apiVersion`, `kind`, `name`, and `environments` are required. Define at least one process or resource. Unknown keys, duplicate keys, unsupported versions, custom tags, multiple documents, collection aliases, cycles, and unknown dependencies fail validation.
- Logical keys start with a letter and contain letters, digits, `_`, or `-` (up to 80 characters). Limits: 100 processes, 100 resources, 20 environments, 48,000 YAML characters; API request body limit is 64 KiB.
- `hosts` defaults to `[local]`; `local` is required. Other names map privately to saved machines. `defaultEnvironment` defaults to the first declared environment. Omitted process bindings mean local.
- Process fields: `name` (defaults to key, unique), `command` (required, up to 4096 characters), `directory` (defaults to `.`, must exist within the real project root), `port` (optional 1–65535), `mode` (`service` default or `task`), `timeoutSeconds` (task wait limit, default 300, range 1–3600), `dependsOn` (process keys), `includeInStack` (default true), and `env`.
- Environment fields: `name`, `bindings` (process → host alias), `resources` (role → reference alias), and `env`. Process variables override environment variables. Values must be quoted strings when YAML would interpret them as numbers/booleans. There is no template language, include directive, automatic `.env` loading, file watcher, or arbitrary registry-file path.
- `${env:VARIABLE}` resolves a complete value from the backend’s launch environment immediately before execution. Credential-like variable names require such references; reserved `RUNTIMEBAY_*` names are blocked. Resolved values are not persisted in YAML or returned in execution previews. Commands themselves can still contain secrets, and subprocesses can print them: review shell text and logs before sharing.

## Existing resource references

```yaml
resources:
  cache: {kind: container, reference: shared-cache}
  database: {kind: database, reference: app-database}
  upstream: {kind: service, reference: shared-api}
  shell: {kind: terminal, reference: worker-shell}
environments:
  development: {}
  integration:
    resources:
      cache: integration-cache
      shell: null
```

Each alias maps in the import UI to an existing saved source: a pinned container link, a saved CloudBeaver connection reference, a service in another workflow, or a machine terminal shortcut. An environment can disable noncontainer references with `null`. Container roles require a target in each environment. Discovery/configuration happens through the existing app controls first. Attaching does not create containers, connect databases, start source services, or open SSH terminals. References are not executable dependency steps; express provisioning/readiness using explicit CLI tasks under `processes`.

Exports replace machine and resource IDs with logical aliases. Private registry mappings and CloudBeaver credentials remain outside the repository file. Shell commands and literal environment values are not automatically anonymized, so exports always require a sharing review. A missing source needs explicit rebinding. The same workflow can describe resources from multiple hosts, but remote-bound managed processes currently fail execution preflight rather than run locally.

## Import and execution guarantees

Preview and apply use a revision fingerprint; stale previews must be regenerated. Existing logical process/environment identities are retained on repeat import. Unrelated manually configured services, environments, and attachments are retained. Removing YAML-managed definitions removes their definitions/associations, not physical resources; shared associations are retained when still referenced. The review lists affected definitions. Import commits the related local registries using a prepared recovery journal and backup. Errors roll back the import; interrupted imports recover on startup before registries are read. Unrecoverable I/O errors preserve the recovery files and require a restart/repair before further mutation.

Execution preflights all selected steps: directories, local host bindings, environment references, current process state, and definition drift. Stack runs include `includeInStack: true` processes plus their dependencies; selecting a manual process runs its dependency closure. Tasks must exit zero before dependents start. Services are considered ready when their owned process starts, not when they pass a health check; use an explicit readiness task if required. YAML-managed starts use reviewed plans; stop remains available after a plan settles. Stop a service before rerunning it with changed environment values.

Cancel or failure stops newly started owned processes in reverse order. Services already running before the plan are reused and remain running. Task timeouts fail the plan. Detached Compose containers, AWS changes, and other CLI side effects are **not** rolled back. Use explicit manual cleanup tasks (for example `docker compose down` with `includeInStack: false`) when appropriate. Runtime Bay does not infer destructive teardown commands.

Definitions and mappings persist; execution progress is app-session state. Existing native process ownership and log controls remain the execution mechanism. The experimental Go worker and distributed orchestration remain separate roadmap work.
