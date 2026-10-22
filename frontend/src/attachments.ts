import { Component, Input, Output, EventEmitter, OnChanges, OnDestroy, inject, signal, computed } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { LoadingService } from './loading/loading.service';
export interface AttachmentSource { kind: 'service' | 'terminal' | 'database' | 'container'; projectId: string; sourceId: string; scope: string }
interface Choice { key: string; source: AttachmentSource; name: string; detail: string; ownership: string }
interface Reference { id: string; workflowId: string; environmentId: string; source: AttachmentSource; name: string }
interface Resource { reference: Reference; key: string; name: string; state: 'saved' | 'unavailable' | 'missing'; detail: string; ownership: string; actions: string[] }
interface Catalog { choices: Choice[]; databaseWorkspaces: { id: string; name: string }[] }
@Component({ selector: 'app-attachments', standalone: true, imports: [FormsModule], templateUrl: './attachments.html' })
export class Attachments implements OnChanges, OnDestroy {
  @Input() projectId = '';
  @Input() environmentId = '';
  @Input() workflowRevision = '';
  @Input() session = '';
  @Output() navigate = new EventEmitter<AttachmentSource>();
  @Output() changed = new EventEmitter<void>();
  resources = signal<Resource[]>([]);
  catalog = signal<Catalog>({ choices: [], databaseWorkspaces: [] });
  open = signal(false);
  busy = signal(false);
  error = signal('');
  formError = signal('');
  filter = signal('');
  kind = signal('all');
  selected = signal('');
  databaseProject = signal('');
  reviewing = signal(false);
  editing = signal<Resource | null>(null);
  visible = computed(() => this.catalog().choices.filter(c => (this.kind() === 'all' || c.source.kind === this.kind()) && (!this.editing() || c.source.kind === this.editing()!.reference.source.kind) && `${c.name} ${c.detail}`.toLowerCase().includes(this.filter().toLowerCase())));
  choice = computed(() => this.catalog().choices.find(c => c.key === this.selected()));
  private revision = 0;
  private controller = new AbortController();
  private tasks = inject(LoadingService).scope();
  private returnFocus: HTMLElement | null = null;
  private timer = setInterval(() => { if (!this.busy()) void this.load(); }, 5000);
  ngOnChanges() { this.revision++; this.controller.abort(); this.controller = new AbortController(); this.open.set(false); this.busy.set(false); this.resources.set([]); this.error.set(''); void this.load(); }
  private path() { return '/api/workflows/' + this.projectId + '/attachments'; }
  private context() { return { environmentId: this.environmentId, workflowRevision: this.workflowRevision }; }
  private async api<T>(suffix = '', method = 'GET', body?: unknown): Promise<T> {
    const response = await fetch(this.path() + suffix, { method, signal: this.controller.signal, headers: { Authorization: 'Bearer ' + this.session, ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    const value = await response.json(); if (!response.ok) throw new Error(value.error || 'Could not update resource references'); return value;
  }
  async load() {
    if (!this.projectId || !this.environmentId) return;
    const revision = this.revision;
    try { const result = await this.api<{ environmentId: string; resources: Resource[] }>(); if (revision === this.revision && result.environmentId === this.environmentId) { this.resources.set(result.resources); this.error.set(''); } }
    catch (error) { if (revision === this.revision && !this.controller.signal.aborted) this.error.set((error as Error).message); }
  }
  attached(choice: Choice) { return this.resources().some(r => r.key === choice.key) || choice.source.kind === 'service' && choice.source.projectId === this.projectId; }
  async show(resource: Resource | null = null) {
    this.returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    this.editing.set(resource); this.selected.set(''); this.filter.set(''); this.kind.set(resource?.reference.source.kind || 'all'); this.reviewing.set(false); this.formError.set(''); this.open.set(true);
    await this.refreshCatalog();
    requestAnimationFrame(() => document.querySelector<HTMLElement>('.attachment-modal input, .attachment-modal button')?.focus());
  }
  async refreshCatalog() {
    const revision = this.revision; this.busy.set(true);
    await this.tasks.run('Reading saved resource references…', async () => {
      try { const result = await this.api<Catalog>('/catalog'); if (revision === this.revision) { this.catalog.set(result); if (!this.databaseProject()) this.databaseProject.set(result.databaseWorkspaces[0]?.id || ''); } }
      catch (error) { if (revision === this.revision) this.formError.set((error as Error).message); }
      finally { if (revision === this.revision) this.busy.set(false); }
    });
  }
  async refreshDatabases() {
    if (!this.databaseProject() || this.busy()) return;
    const revision = this.revision; this.busy.set(true); this.formError.set('');
    await this.tasks.run('Reading saved database connection names…', async () => {
      try { await this.api('/databases/' + this.databaseProject(), 'POST', {}); if (revision === this.revision) await this.refreshCatalog(); }
      catch (error) { if (revision === this.revision) this.formError.set((error as Error).message); }
      finally { if (revision === this.revision) this.busy.set(false); }
    });
  }
  async save() {
    const choice = this.choice(); if (!choice || this.busy() || !this.reviewing()) return;
    const revision = this.revision, editing = this.editing(); this.busy.set(true); this.formError.set('');
    await this.tasks.run(editing ? 'Saving the explicit replacement reference…' : 'Attaching the existing resource…', async () => {
      try {
        await this.api(editing ? '/' + editing.reference.id : '', editing ? 'PUT' : 'POST', { ...this.context(), source: choice.source, ...(editing ? { referenceKey: editing.key } : {}) });
        if (revision !== this.revision) return;
        this.busy.set(false); this.dismiss(); await this.load(); this.changed.emit();
      } catch (error) { if (revision === this.revision) this.formError.set((error as Error).message); }
      finally { if (revision === this.revision) this.busy.set(false); }
    });
  }
  async unlink(resource: Resource) {
    if (this.busy()) return; const revision = this.revision; this.busy.set(true); this.error.set('');
    try { await this.api('/' + resource.reference.id, 'DELETE', { ...this.context(), referenceKey: resource.key }); if (revision === this.revision) await this.load(); }
    catch (error) { if (revision === this.revision) this.error.set((error as Error).message); }
    finally { if (revision === this.revision) this.busy.set(false); }
  }
  async inspect(resource: Resource) {
    if (this.busy()) return; const revision = this.revision; this.busy.set(true); this.error.set('');
    try {
      const resolved = await this.api<Resource>('/' + resource.reference.id + '/resolve', 'POST', { ...this.context(), referenceKey: resource.key });
      if (revision === this.revision && resolved.actions.some(a => ['inspect-service', 'open-terminal', 'open-database-workspace'].includes(a))) this.navigate.emit(resolved.reference.source);
    } catch (error) { if (revision === this.revision) this.error.set((error as Error).message); }
    finally { if (revision === this.revision) this.busy.set(false); }
  }
  openDatabaseWorkspace() { const projectId = this.databaseProject(); if (!projectId || this.busy()) return; this.dismiss(); this.navigate.emit({ kind: 'database', projectId, sourceId: '', scope: '' }); }
  canInspect(resource: Resource) { return resource.actions.some(a => ['inspect-service', 'open-terminal', 'open-database-workspace'].includes(a)); }
  actionLabel(resource: Resource) { return resource.reference.source.kind === 'terminal' ? 'Open terminal' : resource.reference.source.kind === 'service' ? 'Inspect source service' : 'Open database workspace'; }
  dismiss() { if (this.busy()) return; this.open.set(false); this.returnFocus?.focus(); }
  keydown(event: KeyboardEvent) {
    if (event.key === 'Escape') { event.preventDefault(); this.dismiss(); }
    if (event.key === 'Tab') {
      const controls = Array.from(document.querySelectorAll<HTMLElement>('.attachment-modal button:not([disabled]), .attachment-modal input:not([disabled]), .attachment-modal select:not([disabled])'));
      const first = controls[0], last = controls.at(-1);
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
    }
  }
  ngOnDestroy() { this.revision++; clearInterval(this.timer); this.controller.abort(); this.tasks.close(); }
}
