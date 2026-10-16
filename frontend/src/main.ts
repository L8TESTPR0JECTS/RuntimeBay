import { Component, inject, computed, effect, signal, OnDestroy, ElementRef, ViewChild, provideZonelessChangeDetection } from '@angular/core';
import { bootstrapApplication } from '@angular/platform-browser';
import { FormsModule } from '@angular/forms';
import { DatePipe } from '@angular/common';
import { Project, Service, LogEntry, LogResponse } from './types';
import { Terminals, TerminalWorkspace } from './terminals';
import { WorkflowContainers } from './workflow-containers';
import { Databases } from './databases';
import { Containers } from './containers';
import { Appearance } from './appearance';
import { LoadingService } from './loading/loading.service';
import { adjacentTerminal, workspaceShortcut, WorkspaceCommand } from './shortcuts';

const session = window.runtimeBaySession || new URLSearchParams(location.hash.slice(1)).get('session') || '';
if (location.hash) history.replaceState(null, '', location.pathname);

@Component({
  selector: 'app-root', standalone: true, imports: [FormsModule, DatePipe, Containers, Terminals, WorkflowContainers, Databases],
  templateUrl: './app.html'
})
class App implements OnDestroy {
  view = signal<'runtime' | 'containers' | 'hosts' | 'terminal' | 'databases'>('runtime');
  readonly loader = inject(LoadingService);
  private loadingTasks = this.loader.scope();
  readonly appearance = new Appearance(session, this.loader);
  readonly sessionToken = session;
  readonly terminals = new TerminalWorkspace(session);
  projects = signal<Project[]>([]);
  projectId = signal<string | null>(null);
  serviceId = signal<string | null>(null);
  connected = signal(false);
  loading = signal(true);
  error = signal('');
  pending = signal<string[]>([]);
  sheet = signal<'project' | 'service' | 'delete' | null>(null);
  saving = signal(false);
  formError = signal('');
  logs = signal<LogEntry[]>([]);
  logFilter = signal('');
  follow = signal(true);
  project = computed(() => this.projects().find(p => p.id === this.projectId()) || null);
  service = computed(() => this.project()?.services.find(s => s.id === this.serviceId()) || null);
  running = computed(() => this.projects().flatMap(p => p.services).filter(s => this.active(s)).length);
  serviceCount = computed(() => this.projects().reduce((sum, p) => sum + p.services.length, 0));
  visibleLogs = computed(() => {
    const query = this.logFilter().toLowerCase();
    return this.logs().filter(l => !query || l.message.toLowerCase().includes(query) || l.stream.includes(query));
  });
  projectForm = { name: '', path: '' };
  serviceForm = { name: '', command: '', directory: '.', port: null as number | null };
  editId: string | null = null;
  private after = 0;
  private selectionRevision = 0;
  private refreshPromise?: Promise<void>;
  private destroyed = false;
  private timer: ReturnType<typeof setInterval>;
  private controller = new AbortController();
  private returnFocus: HTMLElement | null = null;
  @ViewChild('logBody') logBody?: ElementRef<HTMLElement>;

  private folderListener = (event: Event) => {
    const path = (event as CustomEvent<string>).detail;
    this.projectForm.path = path;
    if (!this.projectForm.name) this.projectForm.name = path.split('/').filter(Boolean).at(-1) || 'Project';
  };
  private keyListener = (event: KeyboardEvent) => {
    if (this.loader.active) return;
    if (event.key === 'Tab' && this.sheet()) {
      const controls = Array.from(document.querySelectorAll<HTMLElement>('.modal button:not([disabled]), .modal input:not([disabled]), .modal textarea:not([disabled])'));
      const first = controls[0], last = controls.at(-1);
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
    }
    if (event.key === 'Escape' && !this.saving()) this.sheet.set(null);
    if (this.appearance.open() || this.sheet() || document.querySelector('.workflow-container-modal')) return;
    const command = workspaceShortcut(event);
    if (command) { event.preventDefault(); this.runCommand(command); }
    if (event.metaKey && !event.ctrlKey && !event.altKey && !event.shiftKey && event.key === ',' && !event.defaultPrevented) { event.preventDefault(); this.appearance.show(); }
  };
  private commandListener = (event: Event) => this.runCommand((event as CustomEvent<WorkspaceCommand>).detail);
  private runCommand(command: WorkspaceCommand) {
    if (this.loader.active || this.appearance.open() || this.sheet() || document.querySelector('.workflow-container-modal, [role="dialog"]')) return;
    if (command === 'new-terminal') void this.newTerminal();
    else if (command === 'import-workflow') this.openProject();
    else if (command === 'previous-terminal' || command === 'next-terminal') {
      const id = adjacentTerminal(this.terminals.tabs().map(tab => tab.id), this.terminals.selected(), command === 'previous-terminal' ? -1 : 1);
      if (id) this.selectTerminal(id);
    }
  }
  constructor() {
    effect(() => {
      if (this.sheet()) {
        this.returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
        setTimeout(() => (document.querySelector<HTMLElement>('.modal input, .modal textarea') || document.querySelector<HTMLElement>('.modal button'))?.focus(), 0);
      } else this.returnFocus?.focus();
    });
    window.addEventListener('runtimebay:folder', this.folderListener);
    window.addEventListener('keydown', this.keyListener);
    window.addEventListener('runtimebay:command', this.commandListener);
    void this.loadingTasks.run('Opening your workspace…', () => this.refresh());
    this.timer = setInterval(() => void this.refresh(), 1500);
  }
  ngOnDestroy() {
    this.terminals.destroy(); this.loadingTasks.close(); this.appearance.destroy(); this.destroyed = true; this.controller.abort(); clearInterval(this.timer);
    window.removeEventListener('runtimebay:folder', this.folderListener);
    window.removeEventListener('keydown', this.keyListener);
    window.removeEventListener('runtimebay:command', this.commandListener);
  }
  private async api<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
    const response = await fetch('/api' + path, {
      method, signal: this.controller.signal,
      headers: { Authorization: 'Bearer ' + session, ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}) },
      ...(body !== undefined ? { body: JSON.stringify(body) } : {})
    });
    const value = await response.json();
    if (!response.ok) throw new Error(value.error || 'Could not complete this operation');
    return value as T;
  }
  refresh(): Promise<void> {
    if (this.destroyed) return Promise.resolve();
    if (!this.refreshPromise) this.refreshPromise = this.readWorkspace().finally(() => { this.refreshPromise = undefined; });
    return this.refreshPromise;
  }
  refreshForeground() { return this.loadingTasks.run('Reconnecting to your workspace…', () => this.refresh()); }
  private async readWorkspace() {
    try {
      const projects = await this.api<Project[]>('/projects');
      this.projects.set(projects); this.connected.set(true);
      if (!projects.some(p => p.id === this.projectId())) this.selectProject(projects[0]?.id || null, false);
      const selected = this.service();
      if (selected && this.view() === 'runtime') await this.refreshLogs(this.projectId()!, selected.id);
    } catch { if (!this.destroyed) this.connected.set(false); }
    finally { this.loading.set(false); }
  }
  private async refreshLogs(project: string, service: string) {
    const revision = this.selectionRevision;
    const response = await this.api<LogResponse>(`/projects/${project}/services/${service}/logs?after=${this.after}`);
    if (revision !== this.selectionRevision || this.projectId() !== project || this.serviceId() !== service) return;
    const fresh = response.entries.filter(line => line.sequence > this.after);
    this.after = Math.max(this.after, response.next);
    if (fresh.length) {
      this.logs.update(entries => [...(response.truncated ? [] : entries), ...fresh].slice(-1000));
      this.scrollLogs();
    }
  }
  async newTerminal() {
    this.view.set('terminal');
    await this.terminals.create(this.projectId());
  }
  selectTerminal(id: string) { this.terminals.selected.set(id); this.view.set('terminal'); }
  selectProject(id: string | null, switchView = true) {
    if (switchView && this.view() !== 'databases') this.view.set('runtime');
    this.projectId.set(id); this.selectService(this.project()?.services[0]?.id || null);
  }
  selectService(id: string | null) {
    if (this.serviceId() === id) return;
    this.selectionRevision++;
    this.serviceId.set(id); this.after = 0; this.logs.set([]); this.logFilter.set('');
    if (id && this.projectId() && this.view() === 'runtime') void this.loadingTasks.run('Loading service output…', () => this.refreshLogs(this.projectId()!, id)).catch(() => {});
  }
  active(service: Service) { return ['running', 'starting', 'stopping'].includes(service.state); }
  busy(service: Service) { return this.pending().includes(service.id) || service.state === 'starting' || service.state === 'stopping'; }
  async action(service: Service, action: 'start' | 'stop' | 'restart') {
    const project = this.project();
    if (!project || this.busy(service)) return;
    const releaseLoading = this.loadingTasks.begin(`${action === 'start' ? 'Starting' : action === 'stop' ? 'Stopping' : 'Restarting'} ${service.name}…`);
    this.pending.update(ids => [...ids, service.id]); this.error.set('');
    this.selectService(service.id);
    try {
      await this.api(`/projects/${project.id}/services/${service.id}/${action}`, 'POST', {});
      await this.refresh();
    } catch (e) { this.error.set((e as Error).message); }
    finally { this.pending.update(ids => ids.filter(id => id !== service.id)); releaseLoading(); }
  }
  async all(action: 'start' | 'stop') {
    const services = this.project()?.services.filter(s => action === 'start' ? !this.active(s) : this.active(s)) || [];
    await Promise.all(services.map(s => this.action(s, action)));
  }
  openProject() { this.view.set('runtime'); this.projectForm = { name: '', path: '' }; this.formError.set(''); this.sheet.set('project'); }
  chooseFolder() { window.webkit?.messageHandlers.runtimeBay.postMessage('chooseFolder'); }
  native() { return !!window.runtimeBayNative; }
  openService(service?: Service) {
    this.editId = service?.id || null;
    this.serviceForm = service ? { name: service.name, command: service.command, directory: service.directory, port: service.port }
      : { name: '', command: '', directory: '.', port: null };
    this.formError.set(''); this.sheet.set('service');
  }
  async saveProject() {
    const releaseLoading = this.loadingTasks.begin('Importing project…');
    this.saving.set(true); this.formError.set('');
    try {
      const project = await this.api<Project>('/projects', 'POST', this.projectForm);
      this.projects.set(await this.api<Project[]>('/projects'));
      this.selectProject(project.id); this.sheet.set(null);
    } catch (e) { this.formError.set((e as Error).message); }
    finally { this.saving.set(false); releaseLoading(); }
  }
  async saveService() {
    const project = this.project(); if (!project) return;
    const releaseLoading = this.loadingTasks.begin('Saving service…');
    this.saving.set(true); this.formError.set('');
    try {
      const service = await this.api<Service>(`/projects/${project.id}/services${this.editId ? '/' + this.editId : ''}`,
        this.editId ? 'PUT' : 'POST', this.serviceForm);
      this.projects.set(await this.api<Project[]>('/projects'));
      this.selectService(service.id); this.sheet.set(null);
    } catch (e) { this.formError.set((e as Error).message); }
    finally { this.saving.set(false); releaseLoading(); }
  }
  async deleteService() {
    const project = this.project(), service = this.service(); if (!project || !service) return;
    const releaseLoading = this.loadingTasks.begin('Removing service…');
    this.saving.set(true); this.formError.set('');
    try {
      await this.api(`/projects/${project.id}/services/${service.id}`, 'DELETE');
      this.projects.set(await this.api<Project[]>('/projects'));
      this.selectService(this.project()?.services[0]?.id || null); this.sheet.set(null);
    } catch (e) { this.formError.set((e as Error).message); }
    finally { this.saving.set(false); releaseLoading(); }
  }
  askDelete() { this.formError.set(''); this.sheet.set('delete'); }
  portLabel(service: Service) {
    if (!service.portInfo) return 'No port configured';
    if (service.portInfo.status === 'available') return `:${service.port} · no listener`;
    if (service.portInfo.status === 'unknown') return `:${service.port} · unavailable`;
    return `:${service.port} · ${service.portInfo.owners.some(o => o.managed) ? 'listening' : 'another process'}`;
  }
  elapsed(service: Service) {
    if (!service.startedAt || !this.active(service)) return service.exitCode === null ? 'Ready to start' : `Exit code ${service.exitCode}`;
    const seconds = Math.floor((Date.now() - Date.parse(service.startedAt)) / 1000);
    return seconds < 60 ? `${seconds}s uptime` : `${Math.floor(seconds / 60)}m uptime`;
  }
  onLogScroll() {
    const el = this.logBody?.nativeElement;
    if (el) this.follow.set(el.scrollHeight - el.scrollTop - el.clientHeight < 60);
  }
  scrollLogs() { if (this.follow()) setTimeout(() => { const el = this.logBody?.nativeElement; if (el) el.scrollTop = el.scrollHeight; }, 0); }
  toggleFollow() { this.follow.update(v => !v); this.scrollLogs(); }
  async copyLogs() {
    const text = this.visibleLogs().map(l => `${l.time} [${l.stream}] ${l.message}`).join('\n');
    try { await navigator.clipboard.writeText(text); }
    catch { this.error.set('Select and copy the log text with ⌘C.'); }
  }
}
bootstrapApplication(App, { providers: [provideZonelessChangeDetection()] }).catch(console.error);
