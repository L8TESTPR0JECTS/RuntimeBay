import { Component, Input, Output, EventEmitter, OnChanges, OnDestroy, inject, signal } from '@angular/core';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { LoadingService } from './loading/loading.service';
interface DatabaseStatus { state: 'closed' | 'starting' | 'stopping' | 'ready' | 'failed'; message: string; framePath: string | null; version: string }
interface Pane { id: string; name: string; url: SafeResourceUrl; loaded: boolean }

@Component({ selector: 'app-databases', standalone: true, templateUrl: './databases.html' })
export class Databases implements OnChanges, OnDestroy {
  @Input() projectId = '';
  @Input() projectName = '';
  @Input() projectPath = '';
  @Input() session = '';
  @Input() visible = false;
  @Output() runtime = new EventEmitter<void>();
  private sanitizer = inject(DomSanitizer);
  private tasks = inject(LoadingService).scope();
  readonly status = signal<DatabaseStatus | null>(null);
  readonly panes = signal<Pane[]>([]);
  readonly error = signal('');
  readonly busy = signal(false);
  private controller = new AbortController();
  private checking = false;
  private revision = 0;
  private timer = setInterval(() => { if (this.visible) void this.refresh(); }, 2000);
  private loadingTickets = new Map<string, () => void>();
  private frameTimeouts = new Map<string, ReturnType<typeof setTimeout>>();
  ngOnChanges() { this.revision++; this.status.set(null); this.error.set(''); if (this.visible && this.projectId) void this.refresh(); }
  private async api<T>(project: string, method = 'GET', body?: unknown): Promise<T> {
    const response = await fetch('/api/projects/' + project + '/databases', { method, signal: this.controller.signal,
      headers: { Authorization: 'Bearer ' + this.session, ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    const value = await response.json(); if (!response.ok) throw new Error(value.error || 'Could not open the database workspace'); return value;
  }
  private accept(project: string, value: DatabaseStatus) {
    if (project !== this.projectId) return;
    this.status.set(value);
    if (value.state === 'ready' && value.framePath && !this.panes().some(pane => pane.id === project)) {
      // Only app-owned, same-origin companion paths can become iframe sources.
      if (value.framePath !== '/database/' + project + '/') throw new Error('Invalid database workspace address');
      this.loadingTickets.set(project, this.tasks.begin('Opening the table browser…'));
      this.frameTimeouts.set(project, setTimeout(() => { this.loadingTickets.get(project)?.(); this.loadingTickets.delete(project); if (project === this.projectId) this.error.set('The database browser is taking longer than expected. Close and reopen the database workspace to reconnect.'); }, 20000));
      this.panes.update(panes => [...panes, { id: project, name: this.projectName, url: this.sanitizer.bypassSecurityTrustResourceUrl(value.framePath!), loaded: false }]);
    }
    if (value.state !== 'ready') {
      this.panes.update(panes => panes.filter(pane => pane.id !== project)); this.loadingTickets.get(project)?.(); this.loadingTickets.delete(project);
    }
  }
  async refresh() {
    if (this.checking || !this.projectId || this.controller.signal.aborted) return;
    const project = this.projectId, revision = this.revision; this.checking = true;
    try { const value = await this.api<DatabaseStatus>(project); if (revision === this.revision) this.accept(project, value); }
    catch (error) { if (revision === this.revision && !this.controller.signal.aborted) this.error.set((error as Error).message); }
    finally { this.checking = false; }
  }
  async start() {
    if (this.busy() || !this.projectId) return;
    const project = this.projectId, revision = this.revision; this.busy.set(true); this.error.set('');
    try {
      const theme = document.documentElement.dataset['appearance'] === 'dark' || getComputedStyle(document.documentElement).colorScheme === 'dark' ? 'dark' : 'light';
      const value = await this.api<DatabaseStatus>(project, 'POST', { theme }); if (revision === this.revision) this.accept(project, value);
    } catch (error) { if (revision === this.revision) this.error.set((error as Error).message); }
    finally { this.busy.set(false); }
  }
  async stop() {
    if (this.busy()) return;
    const project = this.projectId, revision = this.revision; this.busy.set(true); this.error.set('');
    await this.tasks.run('Closing the database workspace…', async () => {
      try { const value = await this.api<DatabaseStatus>(project, 'DELETE'); if (revision === this.revision) this.accept(project, value); }
      catch (error) { if (revision === this.revision) this.error.set((error as Error).message); }
      finally { this.busy.set(false); }
    });
  }
  loaded(project: string) {
    this.panes.update(panes => panes.map(pane => pane.id === project ? { ...pane, loaded: true } : pane));
    this.loadingTickets.get(project)?.(); this.loadingTickets.delete(project);
    clearTimeout(this.frameTimeouts.get(project)); this.frameTimeouts.delete(project);
  }
  ngOnDestroy() { clearInterval(this.timer); for (const timeout of this.frameTimeouts.values()) clearTimeout(timeout); this.controller.abort(); this.tasks.close(); }
}
