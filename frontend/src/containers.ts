import { Component, Input, Output, EventEmitter, OnChanges, OnInit, OnDestroy, signal, computed, effect } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Project } from './types';
import { DockerEnvironment, DockerDiscovery, DockerContainer, DockerOverview, DockerInventory, DockerDetails, DockerOperation } from './docker-types';

@Component({ selector: 'app-containers', standalone: true, imports: [FormsModule], templateUrl: './containers.html' })
export class Containers implements OnInit, OnDestroy, OnChanges {
  @Input() session = '';
  @Input() page: 'containers' | 'hosts' = 'containers';
  @Output() navigate = new EventEmitter<'containers' | 'hosts'>();
  @Input() projects: Project[] = [];
  discovery = signal<DockerDiscovery | null>(null);
  hostName = signal('');
  host = computed(() => this.discovery()?.environments.find(h => h.name === this.hostName()) || null);
  overview = signal<DockerOverview | null>(null);
  inventory = signal<DockerInventory | null>(null);
  details = signal<DockerDetails | null>(null);
  selectedId = signal('');
  selected = computed(() => this.overview()?.containers.find(c => c.id === this.selectedId()) || null);
  tab = signal<'containers' | 'images' | 'storage'>('containers');
  filter = signal('');
  visible = computed(() => {
    const query = this.filter().toLowerCase();
    return this.overview()?.containers.filter(c => `${c.name} ${c.image} ${c.state} ${c.composeProject} ${this.projectName(c.workspaceProject)}`.toLowerCase().includes(query)) || [];
  });
  loading = signal(false);
  refreshingHosts = signal(false);
  error = signal('');
  detailError = signal('');
  pending = signal<string[]>([]);
  modal = signal<'host' | 'create' | 'pull' | 'remove' | null>(null);
  formError = signal('');
  saving = signal(false);
  operation = signal<DockerOperation | null>(null);
  operationBusy = computed(() => !!this.operation() && ['queued', 'running', 'cancelling'].includes(this.operation()!.state));
  hostForm = { name: '', endpoint: '' };
  pullImage = '';
  createForm = { name: '', image: '', arguments: '', environment: '', hostPort: null as number | null, containerPort: null as number | null, restartPolicy: 'no', projectId: '', volume: '', mountPath: '/data', readOnly: false };
  private modalHost: DockerEnvironment | null = null;
  private removeTarget: DockerContainer | null = null;
  private timer?: ReturnType<typeof setInterval>;
  private polling = false;
  private disposed = false;
  private revision = 0;
  private detailsRevision = 0;
  private controller = new AbortController();
  private inventoryAt = 0;
  private keyListener = (event: KeyboardEvent) => {
    if (event.key === 'Escape' && !this.saving()) this.modal.set(null);
    if (event.key === 'Tab' && this.modal()) {
      const controls = Array.from(document.querySelectorAll<HTMLElement>('.dock-modal button:not([disabled]), .dock-modal input:not([disabled]), .dock-modal textarea:not([disabled]), .dock-modal select:not([disabled])'));
      const first = controls[0], last = controls.at(-1);
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
    }
  };
  constructor() {
    effect(() => { if (this.modal()) setTimeout(() => (document.querySelector<HTMLElement>('.dock-modal input, .dock-modal textarea') || document.querySelector<HTMLElement>('.dock-modal button'))?.focus(), 0); });
  }
  ngOnChanges() { if (this.discovery() && this.page === 'containers') void this.refresh(); }
  viewContainers(name: string) { this.selectHost(name); this.navigate.emit('containers'); }
  ngOnInit() {
    window.addEventListener('keydown', this.keyListener);
    void this.api<DockerOperation[]>('/operations').then(operations => {
      if (!this.disposed) this.operation.set(operations.find(o => ['queued', 'running', 'cancelling'].includes(o.state)) || operations[0] || null);
    }).catch(() => {});
    void this.loadHosts(); this.timer = setInterval(() => void this.refresh(), 5000);
  }
  ngOnDestroy() { this.disposed = true; this.controller.abort(); if (this.timer) clearInterval(this.timer); window.removeEventListener('keydown', this.keyListener); }
  private async api<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
    const response = await fetch('/api/docker' + path, { method, signal: this.controller.signal, headers: { Authorization: 'Bearer ' + this.session, ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}) }, ...(body !== undefined ? { body: JSON.stringify(body) } : {}) });
    const value = await response.json(); if (!response.ok) throw new Error(value.error || 'Docker operation failed'); return value as T;
  }
  private path(host: DockerEnvironment) { return '/environments/' + encodeURIComponent(host.name); }
  private endpoint(host: DockerEnvironment) { return '?endpoint=' + encodeURIComponent(host.endpoint); }
  async loadHosts() {
    if (this.refreshingHosts()) return;
    this.refreshingHosts.set(true);
    try {
      const discovered = await this.api<DockerDiscovery>('/environments');
      if (this.disposed) return;
      const previous = this.host(); this.discovery.set(discovered);
      const existing = discovered.environments.find(h => h.name === this.hostName());
      if (!existing || existing.endpoint !== previous?.endpoint) {
        const locals = discovered.environments.filter(h => h.transport === 'local' && h.supported);
        this.selectHost(locals.find(h => h.current)?.name || locals[0]?.name || '');
      } else void this.refresh();
    } catch (e) { if (!this.disposed) this.error.set((e as Error).message); }
    finally { this.refreshingHosts.set(false); }
  }
  selectHost(name: string) {
    this.revision++; this.detailsRevision++; this.hostName.set(name); this.overview.set(null); this.inventory.set(null); this.details.set(null); this.selectedId.set(''); this.error.set(''); this.detailError.set(''); this.filter.set(''); this.inventoryAt = 0;
    void this.refresh();
  }
  async refresh() {
    if (this.polling || this.disposed) return;
    const host = this.host(), revision = this.revision;
    this.polling = true;
    if (!this.overview() && host) this.loading.set(true);
    try {
      const operation = this.operation();
      if (operation && this.operationBusy()) {
        this.operation.set(await this.api<DockerOperation>('/operations/' + operation.id));
        if (!this.operationBusy()) this.inventoryAt = 0;
      }
      if (this.page === 'hosts' || !host || !host.supported) return;
      const overview = await this.api<DockerOverview>(this.path(host) + '/overview' + this.endpoint(host));
      if (revision !== this.revision || this.disposed) return;
      this.overview.set(overview); this.error.set('');
      if (!overview.containers.some(c => c.id === this.selectedId())) { this.selectedId.set(''); this.details.set(null); }
      if (this.tab() !== 'containers' && (Date.now() - this.inventoryAt > 15000 || !this.inventory())) {
        const inventory = await this.api<DockerInventory>(this.path(host) + '/inventory' + this.endpoint(host));
        if (revision !== this.revision) return; this.inventory.set(inventory); this.inventoryAt = Date.now();
      }
      if (this.selectedId() && this.tab() === 'containers') await this.loadDetails(host, this.selectedId());
    } catch (e) { if (revision === this.revision && !this.disposed) { this.error.set((e as Error).message); this.overview.set(null); this.details.set(null); } }
    finally { this.loading.set(false); this.polling = false; if (revision !== this.revision && !this.disposed) void this.refresh(); }
  }
  async selectContainer(container: DockerContainer) {
    this.detailsRevision++; this.selectedId.set(container.id); this.details.set(null); this.detailError.set('');
    const host = this.host(); if (host) await this.loadDetails(host, container.id);
  }
  private async loadDetails(host: DockerEnvironment, id: string) {
    const revision = this.detailsRevision;
    try {
      const details = await this.api<DockerDetails>(this.path(host) + '/containers/' + id + this.endpoint(host));
      if (revision === this.detailsRevision && this.hostName() === host.name && this.selectedId() === id) { this.details.set(details); this.detailError.set(''); }
    } catch (e) { if (revision === this.detailsRevision) this.detailError.set((e as Error).message); }
  }
  selectTab(tab: 'containers' | 'images' | 'storage') { this.tab.set(tab); void this.refresh(); }
  async action(container: DockerContainer, action: 'start' | 'stop' | 'restart' | 'pause' | 'unpause' | 'remove', target?: DockerEnvironment) {
    const host = target || this.host(); if (!host || this.pending().includes(container.id)) return;
    this.pending.update(ids => [...ids, container.id]); this.error.set('');
    try { await this.api(this.path(host) + '/containers/' + container.id + '/' + action, 'POST', { endpoint: host.endpoint }); if (host.name === this.hostName()) await this.refresh(); }
    catch (e) { this.error.set((e as Error).message); throw e; }
    finally { this.pending.update(ids => ids.filter(id => id !== container.id)); }
  }
  runAction(container: DockerContainer, action: 'start' | 'stop' | 'restart' | 'pause' | 'unpause') { void this.action(container, action).catch(() => {}); }
  openModal(type: 'host' | 'create' | 'pull', image = '') {
    this.formError.set(''); this.modalHost = this.host(); this.modal.set(type);
    if (type === 'host') this.hostForm = { name: '', endpoint: '' };
    if (type === 'pull') this.pullImage = image;
    if (type === 'create') this.createForm = { name: '', image, arguments: '', environment: '', hostPort: null, containerPort: null, restartPolicy: 'no', projectId: '', volume: '', mountPath: '/data', readOnly: false };
  }
  askRemove(container: DockerContainer) { this.removeTarget = container; this.modalHost = this.host(); this.formError.set(''); this.modal.set('remove'); }
  removeName() { return this.removeTarget?.name || ''; }
  targetName() { return this.modalHost?.name || ''; }
  targetEndpoint() { return this.modalHost?.endpoint || ''; }
  async removeContainer() {
    if (!this.removeTarget || !this.modalHost) return;
    this.saving.set(true);
    try { await this.action(this.removeTarget, 'remove', this.modalHost); this.modal.set(null); }
    catch (e) { this.formError.set((e as Error).message); }
    finally { this.saving.set(false); }
  }
  async addHost() {
    this.saving.set(true); this.formError.set('');
    try { const host = await this.api<DockerEnvironment>('/environments', 'POST', this.hostForm); await this.loadHosts(); this.selectHost(host.name); this.modal.set(null); }
    catch (e) { this.formError.set((e as Error).message); }
    finally { this.saving.set(false); }
  }
  async createContainer() {
    const host = this.modalHost; if (!host) return;
    this.saving.set(true); this.formError.set('');
    try {
      const f = this.createForm;
      if ((f.hostPort === null) !== (f.containerPort === null)) throw new Error('Fill in both the host and container TCP ports.');
      const environment: Record<string, string> = Object.create(null);
      for (const line of f.environment.split('\n').filter(line => line.trim())) {
        const separator = line.indexOf('=');
        if (separator < 1) throw new Error('Use KEY=VALUE on each environment line.');
        const key = line.slice(0, separator).trim();
        if (Object.prototype.hasOwnProperty.call(environment, key)) throw new Error('Each environment variable must have a unique name.');
        environment[key] = line.slice(separator + 1);
      }
      const result = await this.api<{id: string}>(this.path(host) + '/containers', 'POST', { endpoint: host.endpoint, definition: {
        name: f.name, image: f.image, arguments: f.arguments ? f.arguments.split('\n') : [], environment, restartPolicy: f.restartPolicy, projectId: f.projectId || null,
        ports: f.hostPort !== null && f.containerPort !== null ? [{ host: f.hostPort, container: f.containerPort }] : [],
        mounts: f.volume ? [{ volume: f.volume, path: f.mountPath, readOnly: f.readOnly }] : []
      }});
      this.selectedId.set(result.id); this.tab.set('containers'); this.modal.set(null); await this.refresh();
    } catch (e) { this.formError.set((e as Error).message); }
    finally { this.saving.set(false); }
  }
  async startPull() {
    const host = this.modalHost; if (!host) return;
    this.saving.set(true); this.formError.set('');
    try { this.operation.set(await this.api<DockerOperation>(this.path(host) + '/images/pull', 'POST', { endpoint: host.endpoint, image: this.pullImage })); this.modal.set(null); void this.refresh(); }
    catch (e) { this.formError.set((e as Error).message); }
    finally { this.saving.set(false); }
  }
  async cancelPull() {
    const operation = this.operation(); if (!operation) return;
    try { this.operation.set(await this.api<DockerOperation>('/operations/' + operation.id + '/cancel', 'POST', {})); }
    catch (e) { this.error.set((e as Error).message); }
  }
  projectName(id: string) { return this.projects.find(p => p.id === id)?.name || ''; }
  imageName(repository: string, tag: string) { return repository === '<none>' ? '' : repository + (tag === '<none>' ? '' : ':' + tag); }
  memory(bytes: number) { return (bytes / 1024 / 1024 / 1024).toFixed(1) + ' GiB'; }
  disabled(container: DockerContainer) { return this.pending().includes(container.id) || !this.overview(); }
}
