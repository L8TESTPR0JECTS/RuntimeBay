import { Component, Input, Output, EventEmitter, OnDestroy, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { WorkflowDefinition, WorkflowTarget, WorkflowView } from './types';
import { LoadingService } from './loading/loading.service';
interface ContainerOption { id: string; name: string; image: string; connection: WorkflowDefinition['dockerConnections'][number] }
interface Host { id: string; name: string; local: boolean }

@Component({ selector: 'app-environments', standalone: true, imports: [FormsModule], templateUrl: './environments.html' })
export class Environments implements OnDestroy {
  @Input({ required: true }) workflow!: WorkflowView;
  @Input() session = '';
  @Input() active = false;
  @Output() saved = new EventEmitter<WorkflowView>();
  open = signal(false);
  draft = signal<WorkflowDefinition | null>(null);
  hosts = signal<Host[]>([]);
  containers = signal<ContainerOption[]>([]);
  error = signal('');
  reviewing = signal(false);
  saving = signal(false);
  name = signal('');
  private revision = '';
  private controller = new AbortController();
  private original!: WorkflowDefinition;
  private returnFocus: HTMLElement | null = null;
  private tasks = inject(LoadingService).scope();
  private async api<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
    const response = await fetch('/api' + path, { method, signal: this.controller.signal, headers: { ...(method === 'PUT' ? { 'If-Match': this.revision } : {}), Authorization: 'Bearer ' + this.session, ...(body ? { 'Content-Type': 'application/json' } : {}) }, ...(body ? { body: JSON.stringify(body) } : {}) });
    const value = await response.json(); if (!response.ok) throw new Error(value.error || 'Could not save environments'); return value;
  }
  async show() {
    this.returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    this.error.set(''); this.reviewing.set(false); this.saving.set(true);
    this.revision = this.workflow.revision;
    this.original = structuredClone(this.workflow.definition); this.draft.set(structuredClone(this.original));
    this.name.set(this.selectedName()); this.open.set(true);
    await this.tasks.run('Loading environment targets…', async () => {
      try {
        const [hosts, containers] = await Promise.all([this.api<{ hosts: Host[] }>('/hosts'), this.api<ContainerOption[]>('/workflows/' + this.original.id + '/targets')]);
        this.hosts.set(hosts.hosts); this.containers.set(containers);
      } catch (error) { this.error.set((error as Error).message); }
      finally { this.saving.set(false); requestAnimationFrame(() => document.querySelector<HTMLElement>('.environment-modal select, .environment-modal button')?.focus()); }
    });
  }
  selectedName() { const d = this.draft(); return d?.environments.find(e => e.id === d.defaultEnvironmentId)?.name || ''; }
  select(id: string) { this.draft.update(d => d && ({ ...d, defaultEnvironmentId: id })); this.name.set(this.selectedName()); }
  rename(value: string) { this.name.set(value); this.draft.update(d => d && ({ ...d, environments: d.environments.map(e => e.id === d.defaultEnvironmentId ? { ...e, name: value } : e) })); }
  add() {
    const d = this.draft(); if (!d || d.environments.length >= 20) return;
    const id = crypto.randomUUID(); let number = d.environments.length + 1;
    while (d.environments.some(e => e.name === 'Environment ' + number)) number++;
    this.draft.set({ ...d, defaultEnvironmentId: id, environments: [...d.environments, { id, name: 'Environment ' + number }], bindings: [...d.bindings, ...d.bindings.filter(b => b.environmentId === d.defaultEnvironmentId).map(b => ({ ...b, id: crypto.randomUUID(), environmentId: id }))] });
    this.name.set(this.selectedName());
  }
  remove() {
    const d = this.draft(); if (!d || d.environments.length < 2) return;
    const environments = d.environments.filter(e => e.id !== d.defaultEnvironmentId);
    this.draft.set({ ...d, environments, defaultEnvironmentId: environments[0].id, bindings: d.bindings.filter(b => b.environmentId !== d.defaultEnvironmentId) }); this.name.set(this.selectedName());
  }
  target(resourceId: string) { const d = this.draft()!; return d.bindings.find(b => b.resourceId === resourceId && b.environmentId === d.defaultEnvironmentId)!.target; }
  containerId(resourceId: string) { return this.target(resourceId).linkId || resourceId; }
  bind(resourceId: string, id: string, kind: 'machine' | 'docker') {
    const d = this.draft()!; const option = this.containers().find(c => c.id === id);
    if (kind === 'docker' && !option) return;
    const target: WorkflowTarget = kind === 'machine' ? { kind, id } : { kind, id: option!.connection.id, linkId: id };
    this.draft.set({ ...d,
      machines: kind === 'machine' && !d.machines.some(m => m.id === id) ? [...d.machines, { id }] : d.machines,
      dockerConnections: option && !d.dockerConnections.some(c => c.id === option.connection.id) ? [...d.dockerConnections, option.connection] : d.dockerConnections,
      bindings: d.bindings.map(b => b.resourceId === resourceId && b.environmentId === d.defaultEnvironmentId ? { ...b, target } : b) });
  }
  label(target: WorkflowTarget, resourceId: string, definition = this.draft()!) {
    if (target.kind === 'machine') return this.hosts().find(h => h.id === target.id)?.name || 'Missing machine (' + target.id + ')';
    const option = this.containers().find(c => c.id === (target.linkId || resourceId));
    const connection = definition.dockerConnections.find(c => c.id === target.id);
    return `${option?.name || 'Missing container'} · ${connection?.context || target.id} · ${connection?.endpoint || 'unresolved'}`;
  }
  changes() {
    const d = this.draft()!; const before = this.original; const changes: string[] = [];
    const oldName = before.environments.find(e => e.id === before.defaultEnvironmentId)?.name;
    if (before.defaultEnvironmentId !== d.defaultEnvironmentId) changes.push(`Selected environment: ${oldName} → ${this.selectedName()}`);
    for (const old of before.environments) {
      const next = d.environments.find(e => e.id === old.id);
      if (!next) changes.push(`Remove environment: ${old.name} (saved bindings only)`);
      else if (next.name !== old.name) changes.push(`Rename environment: ${old.name} → ${next.name}`);
    }
    for (const env of d.environments) {
      if (!before.environments.some(e => e.id === env.id)) changes.push(`Create environment: ${env.name}`);
      for (const b of d.bindings.filter(b => b.environmentId === env.id)) {
        const old = before.bindings.find(v => v.resourceId === b.resourceId && v.environmentId === env.id);
        if (!old || old.target.kind !== b.target.kind || old.target.id !== b.target.id || (old.target.linkId || old.resourceId) !== (b.target.linkId || b.resourceId)) changes.push(`${env.name} / ${d.resources.find(r => r.id === b.resourceId)?.name}: ${old ? this.label(old.target, b.resourceId, before) : 'new binding'} → ${this.label(b.target, b.resourceId)}`);
      }
    }
    if (before.defaultEnvironmentId !== d.defaultEnvironmentId) for (const resource of d.resources) {
      const old = before.bindings.find(b => b.environmentId === before.defaultEnvironmentId && b.resourceId === resource.id)!;
      changes.push(`Effective ${resource.name}: ${this.label(old.target, resource.id, before)} → ${this.label(this.target(resource.id), resource.id)}`);
    }
    return changes;
  }
  review() {
    this.error.set(''); const names = this.draft()!.environments.map(e => e.name.trim().toLowerCase());
    if (names.some(n => !n) || new Set(names).size !== names.length) { this.error.set('Give each environment a unique name.'); return; }
    this.reviewing.set(true);
  }
  async apply() {
    if (this.saving() || !this.reviewing() || this.active) return;
    this.saving.set(true); this.error.set('');
    await this.tasks.run('Saving environment bindings…', async () => {
      try {
        // Polling must not silently overwrite edits made since this editor opened.
        const current = await this.api<WorkflowView>('/workflows/' + this.original.id);
        if (JSON.stringify(current.definition) !== JSON.stringify(this.original)) throw new Error('This workflow changed while you were editing. Close and reopen Environments to review the latest definition.');
        const result = await this.api<WorkflowView>('/workflows/' + this.original.id, 'PUT', this.draft());
        this.saved.emit(result); this.saving.set(false); this.dismiss();
      } catch (error) { this.error.set((error as Error).message); }
      finally { this.saving.set(false); }
    });
  }
  ngOnDestroy() { this.controller.abort(); this.tasks.close(); }
  dismiss() { if (this.saving()) return; this.open.set(false); this.returnFocus?.focus(); }
  keydown(event: KeyboardEvent) {
    if (event.key === 'Escape') { event.preventDefault(); this.dismiss(); }
    if (event.key === 'Tab') {
      const controls = Array.from(document.querySelectorAll<HTMLElement>('.environment-modal button:not([disabled]), .environment-modal input:not([disabled]), .environment-modal select:not([disabled])'));
      const first = controls[0], last = controls.at(-1);
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
    }
  }
}
