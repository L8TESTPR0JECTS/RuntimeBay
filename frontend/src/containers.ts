import { Component, Input, Output, EventEmitter, OnChanges, SimpleChanges, OnInit, OnDestroy, inject, signal, computed, effect } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { LoadingService } from './loading/loading.service';
import { Project } from './types';
import { dockerHostName, DockerEnvironment, DockerDiscovery, DockerContainer, DockerOverview, DockerInventory, DockerDetails, DockerOperation } from './docker-types';

@Component({ selector: 'app-containers', standalone: true, imports: [FormsModule], templateUrl: './containers.html' })
export class Containers implements OnInit, OnDestroy, OnChanges {
  readonly hostLabel = dockerHostName;
  private loader = inject(LoadingService);
  private loadingTasks = this.loader.scope();
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
  stateFilter = signal<'all' | 'running' | 'exited'>('all');
  runningCount = computed(() => this.overview()?.containers.filter(container => container.state === 'running').length || 0);
  exitedCount = computed(() => this.overview()?.containers.filter(container => this.isExited(container)).length || 0);
  visible = computed(() => {
    const query = this.filter().toLowerCase();
    const state = this.stateFilter();
    return this.overview()?.containers.filter(c => {
      const matchesQuery = `${c.name} ${c.image} ${c.state} ${c.composeProject} ${this.projectName(c.workspaceProject)}`.toLowerCase().includes(query);
      const matchesState = state === 'all' || (state === 'running' ? c.state === 'running' : this.isExited(c));
      return matchesQuery && matchesState;
    }) || [];
  });
  loading = signal(false);
  refreshingHosts = signal(false);
  error = signal('');
  detailError = signal('');
  pending = signal<string[]>([]);
  modal = signal<'host' | 'create' | 'pull' | 'remove' | 'remove-host' | null>(null);
  formError = signal('');
  saving = signal(false);
  operation = signal<DockerOperation | null>(null);
  operationBusy = computed(() => !!this.operation() && ['queued', 'running', 'cancelling'].includes(this.operation()!.state));
  hostForm = { name: '', endpoint: '', description: '' };
  editingHost = signal<DockerEnvironment | null>(null);
  removingHost = signal<DockerEnvironment | null>(null);
  pullImage = '';
  createForm = { name: '', image: '', arguments: '', environment: '', hostPort: null as number | null, containerPort: null as number | null, restartPolicy: 'no', projectId: '', volume: '', mountPath: '/data', readOnly: false };
  private modalHost: DockerEnvironment | null = null;
  private removeTarget: DockerContainer | null = null;
  private timer?: ReturnType<typeof setInterval>;
  private refreshPromise?: Promise<void>;
  private disposed = false;
  private revision = 0;
  private detailsRevision = 0;
  private controller = new AbortController();
  private inventoryAt = 0;
  private keyListener = (event: KeyboardEvent) => {
    if (this.loader.active) return;
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
  ngOnChanges(changes: SimpleChanges) { if (changes['page'] && this.discovery() && this.page === 'containers') void this.refresh(true); }
  viewContainers(name: string) { this.page = 'containers'; this.selectHost(name); }
  showConnections() { this.page = 'hosts'; }
  ngOnInit() {
    window.addEventListener('keydown', this.keyListener);
    void this.api<DockerOperation[]>('/operations').then(operations => {
      if (!this.disposed) this.operation.set(operations.find(o => ['queued', 'running', 'cancelling'].includes(o.state)) || operations[0] || null);
    }).catch(() => {});
    void this.loadHosts(); this.timer = setInterval(() => void this.refresh(), 5000);
  }
  ngOnDestroy() { this.loadingTasks.close(); this.disposed = true; this.controller.abort(); if (this.timer) clearInterval(this.timer); window.removeEventListener('keydown', this.keyListener); }
  private async api<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
    const response = await fetch('/api/docker' + path, { method, signal: this.controller.signal, headers: { Authorization: 'Bearer ' + this.session, ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}) }, ...(body !== undefined ? { body: JSON.stringify(body) } : {}) });
    const value = await response.json(); if (!response.ok) throw new Error(value.error || 'Docker operation failed'); return value as T;
  }
  private path(host: DockerEnvironment) { return '/environments/' + encodeURIComponent(host.name); }
  private endpoint(host: DockerEnvironment) { return '?endpoint=' + encodeURIComponent(host.endpoint); }
  async loadHosts() {
    if (this.refreshingHosts()) return;
    const releaseLoading = this.loadingTasks.begin('Finding your Docker hosts…');
    this.refreshingHosts.set(true);
    try {
      const discovered = await this.api<DockerDiscovery>('/environments');
      if (this.disposed) return;
      const previous = this.host(); this.discovery.set(discovered);
      const existing = discovered.environments.find(h => h.name === this.hostName());
      if (!existing || existing.endpoint !== previous?.endpoint) {
        const locals = discovered.environments.filter(h => h.transport === 'local' && h.supported);
        this.selectHost(discovered.environments.find(h => h.current && h.supported)?.name || locals[0]?.name || discovered.environments.find(h => h.supported)?.name || '');
      } else void this.refresh();
    } catch (e) { if (!this.disposed) this.error.set((e as Error).message); }
    finally { this.refreshingHosts.set(false); releaseLoading(); }
  }
  selectHost(name: string) {
    this.revision++; this.detailsRevision++; this.hostName.set(name); this.overview.set(null); this.inventory.set(null); this.details.set(null); this.selectedId.set(''); this.error.set(''); this.detailError.set(''); this.filter.set(''); this.inventoryAt = 0;
    void this.refresh(true);
  }
  refresh(foreground = false): Promise<void> {
    const work = () => {
      if (!this.refreshPromise) this.refreshPromise = this.refreshOnce().finally(() => { this.refreshPromise = undefined; });
      return this.refreshPromise;
    };
    return foreground && this.page === 'containers' ? this.loadingTasks.run(`Loading ${this.hostLabel(this.host()) || 'containers'}…`, work) : work();
  }
  private async refreshOnce() {
    if (this.disposed) return;
    const host = this.host(), revision = this.revision;
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
    finally { this.loading.set(false); if (revision !== this.revision && !this.disposed) await this.refreshOnce(); }
  }
  async selectContainer(container: DockerContainer) {
    this.detailsRevision++; this.selectedId.set(container.id); this.details.set(null); this.detailError.set('');
    const host = this.host(); if (host) await this.loadingTasks.run('Loading container details…', () => this.loadDetails(host, container.id));
  }
  private async loadDetails(host: DockerEnvironment, id: string) {
    const revision = this.detailsRevision;
    try {
      const details = await this.api<DockerDetails>(this.path(host) + '/containers/' + id + this.endpoint(host));
      if (revision === this.detailsRevision && this.hostName() === host.name && this.selectedId() === id) { this.details.set(details); this.detailError.set(''); }
    } catch (e) { if (revision === this.detailsRevision) this.detailError.set((e as Error).message); }
  }
  selectTab(tab: 'containers' | 'images' | 'storage') { this.tab.set(tab); void this.refresh(true); }
  async action(container: DockerContainer, action: 'start' | 'stop' | 'restart' | 'pause' | 'unpause' | 'remove', target?: DockerEnvironment) {
    const host = target || this.host(); if (!host || this.pending().includes(container.id)) return;
    const releaseLoading = this.loadingTasks.begin(`Updating ${container.name}…`);
    this.pending.update(ids => [...ids, container.id]); this.error.set('');
    try { await this.api(this.path(host) + '/containers/' + container.id + '/' + action, 'POST', { endpoint: host.endpoint }); if (host.name === this.hostName()) await this.refresh(); }
    catch (e) { this.error.set((e as Error).message); throw e; }
    finally { this.pending.update(ids => ids.filter(id => id !== container.id)); releaseLoading(); }
  }
  runAction(container: DockerContainer, action: 'start' | 'stop' | 'restart' | 'pause' | 'unpause') { void this.action(container, action).catch(() => {}); }
  openModal(type: 'host' | 'create' | 'pull', image = '') {
    this.formError.set(''); this.modalHost = this.host(); this.modal.set(type);
    if (type === 'host') { this.editingHost.set(null); this.hostForm = { name: '', endpoint: '', description: '' }; }
    if (type === 'pull') this.pullImage = image;
    if (type === 'create') this.createForm = { name: '', image, arguments: '', environment: '', hostPort: null, containerPort: null, restartPolicy: 'no', projectId: '', volume: '', mountPath: '/data', readOnly: false };
  }
  editHost(host: DockerEnvironment) {
    if (host.name === 'default') return;
    this.formError.set(''); this.editingHost.set({ ...host });
    this.hostForm = { name: host.name, endpoint: host.endpoint, description: host.description };
    this.modal.set('host');
  }
  askRemoveHost(host: DockerEnvironment) {
    if (host.name === 'default' || host.current) return;
    this.formError.set(''); this.removingHost.set({ ...host }); this.modal.set('remove-host');
  }
  async makeDefault(host: DockerEnvironment) {
    if (host.current || !host.supported || this.saving()) return;
    const releaseLoading = this.loadingTasks.begin(`Making ${this.hostLabel(host)} the default host…`);
    this.saving.set(true); this.error.set('');
    try {
      const discovery = await this.api<DockerDiscovery>(this.path(host) + '/default', 'POST', { expectedEndpoint: host.endpoint });
      this.discovery.set(discovery); this.selectHost(host.name);
      if (discovery.error) this.error.set(discovery.error);
    } catch (e) { this.error.set((e as Error).message); }
    finally { this.saving.set(false); releaseLoading(); }
  }
  async removeHost() {
    const host = this.removingHost(); if (!host || this.saving()) return;
    const releaseLoading = this.loadingTasks.begin(`Removing ${this.hostLabel(host)}…`);
    this.saving.set(true); this.formError.set('');
    try {
      await this.api(this.path(host), 'DELETE', { expectedEndpoint: host.endpoint });
      if (host.name === this.hostName()) {
        this.revision++; this.detailsRevision++; this.hostName.set(''); this.overview.set(null); this.inventory.set(null); this.details.set(null); this.selectedId.set(''); this.error.set(''); this.detailError.set('');
      }
      await this.loadHosts(); this.modal.set(null); this.removingHost.set(null);
    } catch (e) { this.formError.set((e as Error).message); }
    finally { this.saving.set(false); releaseLoading(); }
  }
  askRemove(container: DockerContainer) { this.removeTarget = container; this.modalHost = this.host(); this.formError.set(''); this.modal.set('remove'); }
  removeName() { return this.removeTarget?.name || ''; }
  targetName() { return this.hostLabel(this.modalHost); }
  targetEndpoint() { return this.modalHost?.endpoint || ''; }
  async removeContainer() {
    if (!this.removeTarget || !this.modalHost) return;
    const releaseLoading = this.loadingTasks.begin('Removing container…');
    this.saving.set(true);
    try { await this.action(this.removeTarget, 'remove', this.modalHost); this.modal.set(null); }
    catch (e) { this.formError.set((e as Error).message); }
    finally { this.saving.set(false); releaseLoading(); }
  }
  async addHost() {
    if (this.saving()) return;
    const editing = this.editingHost();
    const releaseLoading = this.loadingTasks.begin(editing ? 'Saving host changes…' : 'Adding remote host…');
    this.saving.set(true); this.formError.set('');
    try {
      const host = editing
        ? await this.api<DockerEnvironment>(this.path(editing), 'PUT', { expectedEndpoint: editing.endpoint, endpoint: this.hostForm.endpoint.trim(), description: this.hostForm.description.trim() })
        : await this.api<DockerEnvironment>('/environments', 'POST', { name: this.hostForm.name.trim(), endpoint: this.hostForm.endpoint.trim() });
      // Invalidate in-flight reads before replacing the address snapshot.
      this.revision++; this.detailsRevision++; this.overview.set(null); this.inventory.set(null); this.details.set(null); this.selectedId.set(''); this.error.set(''); this.detailError.set('');
      await this.loadHosts(); this.selectHost(host.name); this.modal.set(null);
    }
    catch (e) { this.formError.set((e as Error).message); }
    finally { this.saving.set(false); releaseLoading(); }
  }
  async createContainer() {
    const host = this.modalHost; if (!host) return;
    const releaseLoading = this.loadingTasks.begin('Creating container…');
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
      const result = await this.api<{id: string; warning?: string}>(this.path(host) + '/containers', 'POST', { endpoint: host.endpoint, definition: {
        name: f.name, image: f.image, arguments: f.arguments ? f.arguments.split('\n') : [], environment, restartPolicy: f.restartPolicy, projectId: f.projectId || null,
        ports: f.hostPort !== null && f.containerPort !== null ? [{ host: f.hostPort, container: f.containerPort }] : [],
        mounts: f.volume ? [{ volume: f.volume, path: f.mountPath, readOnly: f.readOnly }] : []
      }});
      this.selectedId.set(result.id); this.tab.set('containers'); this.modal.set(null); await this.refresh();
      if (result.warning) this.error.set(result.warning);
    } catch (e) { this.formError.set((e as Error).message); }
    finally { this.saving.set(false); releaseLoading(); }
  }
  async startPull() {
    const host = this.modalHost; if (!host) return;
    const releaseLoading = this.loadingTasks.begin('Starting image pull…');
    this.saving.set(true); this.formError.set('');
    try { this.operation.set(await this.api<DockerOperation>(this.path(host) + '/images/pull', 'POST', { endpoint: host.endpoint, image: this.pullImage })); this.modal.set(null); void this.refresh(); }
    catch (e) { this.formError.set((e as Error).message); }
    finally { this.saving.set(false); releaseLoading(); }
  }
  async cancelPull() {
    const operation = this.operation(); if (!operation) return;
    const releaseLoading = this.loadingTasks.begin('Cancelling image pull…');
    try { this.operation.set(await this.api<DockerOperation>('/operations/' + operation.id + '/cancel', 'POST', {})); }
    catch (e) { this.error.set((e as Error).message); }
    finally { releaseLoading(); }
  }
  projectName(id: string) { return this.projects.find(p => p.id === id)?.name || ''; }
  isExited(container: DockerContainer) { return ['exited', 'dead'].includes(container.state); }
  toggleStateFilter(state: 'running' | 'exited') { this.stateFilter.update(current => current === state ? 'all' : state); }
  resetContainerFilters() { this.filter.set(''); this.stateFilter.set('all'); }
  imageName(repository: string, tag: string) { return repository === '<none>' ? '' : repository + (tag === '<none>' ? '' : ':' + tag); }
  memory(bytes: number) { return (bytes / 1024 / 1024 / 1024).toFixed(1) + ' GiB'; }
  disabled(container: DockerContainer) { return this.pending().includes(container.id) || !this.overview(); }
}
