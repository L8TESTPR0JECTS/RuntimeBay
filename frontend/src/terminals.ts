import { AfterViewInit, Component, ElementRef, OnDestroy, ViewChild, effect, input, signal, computed } from '@angular/core';
import type { Terminal } from '@xterm/xterm';
import type { FitAddon } from '@xterm/addon-fit';

export interface ShellTab { id: string; name: string; directory: string; exitCode: number | null; hostId: string; hostName: string; connected?: boolean }
interface Output { data: string; next: number; truncated: boolean; exitCode: number | null }

/** Owns the tab collection; the backend owns shell lifetimes across view changes and reloads. */
export class TerminalWorkspace {
  readonly tabs = signal<ShellTab[]>([]);
  readonly selected = signal<string | null>(null);
  readonly selectedTab = computed(() => this.tabs().find(tab => tab.id === this.selected()));
  readonly error = signal('');
  readonly creating = signal(false);
  readonly closing = signal<string[]>([]);
  private controller = new AbortController();
  constructor(private token: string) { void this.restore(); }
  async api<T>(path: string, method = 'GET', body?: unknown, signal = this.controller.signal): Promise<T> {
    const response = await fetch('/api/terminals' + path, { method, signal,
      headers: { Authorization: 'Bearer ' + this.token, ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    const result = await response.json();
    if (!response.ok) throw new Error(result.error || 'Could not connect to this terminal');
    return result;
  }
  private async restore() {
    try { const tabs = await this.api<ShellTab[]>(''); this.tabs.update(current => [...tabs, ...current.filter(tab => !tabs.some(existing => existing.id === tab.id))]); }
    catch (error) { if (!this.controller.signal.aborted) this.error.set((error as Error).message); }
  }
  async create(projectId: string | null, hostId?: string): Promise<boolean> {
    if (this.creating()) return false;
    this.creating.set(true); this.error.set('');
    try {
      const tab = await this.api<ShellTab>('', 'POST', { projectId, ...(hostId ? { hostId } : {}) });
      this.tabs.update(tabs => [...tabs, tab]); this.selected.set(tab.id); return true;
    } catch (error) { this.error.set((error as Error).message); return false; }
    finally { this.creating.set(false); }
  }
  async rename(id: string,name: string) {
    const tab=await this.api<ShellTab>('/'+id,'PUT',{name});this.tabs.update(tabs=>tabs.map(value=>value.id===id?tab:value));
  }
  async close(id: string) {
    if (this.closing().includes(id)) return;
    this.closing.update(ids => [...ids, id]); this.error.set('');
    try {
      await this.api('/' + id, 'DELETE');
      const tabs = this.tabs(), index = tabs.findIndex(tab => tab.id === id), remaining = tabs.filter(tab => tab.id !== id);
      this.tabs.set(remaining);
      if (this.selected() === id) this.selected.set(remaining[Math.min(index, remaining.length - 1)]?.id || null);
    } catch (error) { this.error.set((error as Error).message); }
    finally { this.closing.update(ids => ids.filter(value => value !== id)); }
  }
  destroy() { this.controller.abort(); }
}
interface Renderer { terminal: Terminal; fit: FitAddon; element: HTMLElement; abort: AbortController; next: number; input: Promise<void>; queued: number; exited: boolean }

@Component({
  selector: 'app-terminals', standalone: true,
  template: `<section class="shell-workspace" [hidden]="!visible()">
    <div class="shell-heading"><div><div class="eyebrow">YOUR SHELL, RIGHT HERE</div><h1>{{ selectedTab()?.name || 'Terminals' }}</h1><p class="subtitle">{{ selectedTab()?.directory || 'Open a terminal from the workspace sidebar.' }}</p></div><div class="shell-name-actions">@if (renaming()) { <input #terminalName aria-label="Terminal name" maxlength="128" [value]="selectedTab()?.name || ''" (keyup.enter)="rename(terminalName.value)" (keyup.escape)="renaming.set(false)"><button class="secondary" (click)="rename(terminalName.value)">Save name</button><button class="text-button" (click)="renaming.set(false)">Cancel</button> } @else { <button class="secondary" (click)="renaming.set(true)" [disabled]="!selectedTab()">Rename terminal</button> }<span class="local-pill">{{ selectedTab()?.hostId && selectedTab()?.hostId !== 'local' ? 'SSH · ' + selectedTab()?.hostName : 'zsh · This Mac' }}</span></div></div>
    @if (workspace().error()) { <div class="notice" role="alert">{{ workspace().error() }}<button (click)="workspace().error.set('')" aria-label="Dismiss terminal error">×</button></div> }
    <div #surfaces class="shell-surfaces" aria-label="Workspace terminals"></div>
    @if (!selectedTab()) { <div class="shell-empty">Choose a terminal from the sidebar, or press ＋ Terminal to open a shell.</div> }
    <div class="shell-foot"><span>{{ selectedTab()?.exitCode != null ? 'Shell exited · code ' + selectedTab()!.exitCode : 'Independent shell · stays open as you switch views' }}</span><span>⌃ C Interrupt · ⌃ D Exit</span></div>
  </section>`
})
export class Terminals implements AfterViewInit, OnDestroy {
  readonly workspace = input.required<TerminalWorkspace>();
  readonly visible = input(false);
  @ViewChild('surfaces') surfaces!: ElementRef<HTMLElement>;
  readonly renaming=signal(false);
  async rename(name: string) {
    const id=this.workspace().selected();if(!id)return;
    try{await this.workspace().rename(id,name);this.renaming.set(false);}catch(error){this.workspace().error.set((error as Error).message);}
  }
  private ready = signal(false);
  private renderers = new Map<string, Renderer>();
  private observer?: ResizeObserver;
  private themeObserver?: MutationObserver;
  private frame = 0;
  private destroyed = false;
  private mounting = new Set<string>();
  private focusId: string | null = null;
  private resizeListener = () => this.fit();
  selectedTab() { return this.workspace().tabs().find(tab => tab.id === this.workspace().selected()); }
  constructor() {
    effect(() => {
      if (!this.ready()) return;
      const workspace = this.workspace(), tabs = workspace.tabs(), selected = workspace.selected(), visible = this.visible();
      for (const [id, renderer] of this.renderers) if (!tabs.some(tab => tab.id === id)) {
        renderer.abort.abort(); renderer.terminal.dispose(); renderer.element.remove(); this.renderers.delete(id);
      }
      // Keep existing emulators mounted, preserving scrollback and alternate screens.
      for (const tab of tabs) {
        const renderer = this.renderers.get(tab.id);
        if (renderer) { renderer.element.hidden = !visible || selected !== tab.id; renderer.terminal.textarea?.setAttribute('aria-label', 'Terminal input · ' + tab.name); }
      }
      if (visible && selected) {
        const tab = tabs.find(tab => tab.id === selected);
        if (tab && !this.renderers.has(selected) && !this.mounting.has(selected)) void this.mount(tab).catch(error => this.workspace().error.set((error as Error).message));
        cancelAnimationFrame(this.frame);
        this.frame = requestAnimationFrame(() => {
          const renderer = this.renderers.get(selected);
          this.fitRenderer(renderer);
          if (this.focusId !== selected) { renderer?.terminal.focus(); this.focusId = selected; }
        });
      } else this.focusId = null;
    });
  }
  ngAfterViewInit() {
    window.addEventListener('resize', this.resizeListener);
    this.observer = new ResizeObserver(() => this.fit()); this.observer.observe(this.surfaces.nativeElement);
    this.themeObserver = new MutationObserver(() => {
      for (const renderer of this.renderers.values()) {
        renderer.terminal.options.theme = this.theme();
        const fontSize = this.fontSize();
        if (renderer.terminal.options.fontSize !== fontSize) { renderer.terminal.options.fontSize = fontSize; }
      }
      this.fit();
    });
    this.themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['style', 'class', 'data-appearance', 'data-palette'] });
    this.ready.set(true);
  }
  private theme() {
    const style = getComputedStyle(document.documentElement);
    return { background: style.getPropertyValue('--terminal').trim() || '#141d21', foreground: '#e0e6e8', cursor: '#8fe3c2', selectionBackground: '#50657599', black: '#243139', red: '#ff8e91', green: '#8fe3aa', yellow: '#eed28a', blue: '#8bbcff', magenta: '#dca2f3', cyan: '#80d9e4', white: '#e0e6e8', brightBlack: '#91a2ad', brightRed: '#ffb2b4', brightGreen: '#b1f0c2', brightYellow: '#ffe6a7', brightBlue: '#b4d5ff', brightMagenta: '#edc4ff', brightCyan: '#b4f4fc', brightWhite: '#ffffff' };
  }
  private fontSize() {
    const scale = Number.parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--font-scale')) || 1;
    return 13 * scale;
  }
  private async mount(tab: ShellTab) {
    this.mounting.add(tab.id);
    if(tab.connected === false)try{
      const opened=await this.workspace().api<ShellTab>('/'+tab.id+'/open','POST',{});
      this.workspace().tabs.update(tabs=>tabs.map(value=>value.id===tab.id?opened:value));
    }catch(error){this.mounting.delete(tab.id);throw error;}

    let TerminalClass: typeof import('@xterm/xterm').Terminal, FitClass: typeof import('@xterm/addon-fit').FitAddon;
    try {
      const [xterm, addon] = await Promise.all([import('@xterm/xterm'), import('@xterm/addon-fit')]);
      TerminalClass = xterm.Terminal; FitClass = addon.FitAddon;
    } finally { this.mounting.delete(tab.id); }
    if (this.destroyed || !this.workspace().tabs().some(value => value.id === tab.id)) return;
    const element = document.createElement('div'); element.className = 'shell-surface'; this.surfaces.nativeElement.append(element);
    const terminal = new TerminalClass({ cursorBlink: true, fontFamily: 'Menlo, Monaco, monospace', fontSize: this.fontSize(), lineHeight: 1.35, scrollback: 10000, scrollOnUserInput: true, scrollOnEraseInDisplay: true, theme: this.theme(), screenReaderMode: true });
    const fit = new FitClass(); terminal.loadAddon(fit); terminal.open(element);
    terminal.textarea?.setAttribute('aria-label', 'Terminal input · ' + tab.name);
    const renderer: Renderer = { terminal, fit, element, abort: new AbortController(), next: 0, input: Promise.resolve(), queued: 0, exited: false };
    this.renderers.set(tab.id, renderer);
    terminal.attachCustomKeyEventHandler(event => {
      // Keep macOS copy/paste/select-all in the web view; Ctrl shortcuts belong to the shell.
      if (event.metaKey && !event.ctrlKey && !event.altKey && !event.shiftKey && event.key.toLowerCase() === 'a') { terminal.selectAll(); event.preventDefault(); return false; }
      if (event.metaKey) return false;
      return true;
    });
    terminal.onData(data => {
      if (renderer.exited || renderer.abort.signal.aborted) return;
      if (renderer.queued + data.length > 1024 * 1024) { this.workspace().error.set('The terminal input queue is full. Wait for the shell to catch up before pasting again.'); return; }
      renderer.queued += data.length;
      renderer.input = renderer.input.then(async () => {
        // Split on code points, preserving UTF-8 and keeping each request below 16 KiB.
        const characters = Array.from(data);
        for (let i = 0; i < characters.length; i += 2048) await this.workspace().api('/' + tab.id + '/input', 'POST', { data: characters.slice(i, i + 2048).join('') }, renderer.abort.signal);
      }).catch(error => { if (!renderer.abort.signal.aborted) this.workspace().error.set((error as Error).message); }).finally(() => { renderer.queued -= data.length; });
    });
    terminal.onResize(size => { void this.workspace().api('/' + tab.id + '/resize', 'POST', { columns: size.cols, rows: size.rows }, renderer.abort.signal).catch(error => { if (!renderer.abort.signal.aborted && !renderer.exited) this.workspace().error.set((error as Error).message); }); });
    element.hidden = !this.visible() || this.workspace().selected() !== tab.id;
    requestAnimationFrame(() => {
      if (!renderer.abort.signal.aborted && !element.hidden) { this.fitRenderer(renderer); terminal.focus(); }
    });
    void this.read(tab.id, renderer);
  }
  private async read(id: string, renderer: Renderer) {
    while (!renderer.abort.signal.aborted) {
      try {
        const output = await this.workspace().api<Output>('/' + id + '/output?after=' + renderer.next, 'GET', undefined, renderer.abort.signal);
        if (renderer.abort.signal.aborted) return;
        if (output.truncated) { renderer.terminal.reset(); renderer.terminal.writeln('[Earlier terminal output was discarded: the live buffer holds 1 MiB.]'); }
        if (output.data) await new Promise<void>(resolve => renderer.terminal.write(Uint8Array.from(atob(output.data), value => value.charCodeAt(0)), resolve));
        renderer.next = output.next;
        if (output.exitCode !== null) {
          renderer.exited = true; renderer.terminal.options.disableStdin = true;
          renderer.terminal.writeln('\r\n[Shell exited · code ' + output.exitCode + ']');
          this.workspace().tabs.update(tabs => tabs.map(tab => tab.id === id ? { ...tab, exitCode: output.exitCode } : tab)); return;
        }
      } catch (error) {
        if (renderer.abort.signal.aborted) return;
        this.workspace().error.set((error as Error).message);
        await new Promise<void>(resolve => {
          const done = () => { clearTimeout(timeout); renderer.abort.signal.removeEventListener('abort', done); resolve(); };
          const timeout = setTimeout(done, 1500); renderer.abort.signal.addEventListener('abort', done, { once: true });
        });
      }
    }
  }
  private fitRenderer(renderer?: Renderer) {
    if (!renderer || renderer.element.hidden || renderer.abort.signal.aborted) return;
    const buffer = renderer.terminal.buffer.active;
    const following = buffer.viewportY >= buffer.baseY;
    renderer.fit.fit();
    if (following) renderer.terminal.scrollToBottom();
  }
  private fit() { if (this.visible()) this.fitRenderer(this.renderers.get(this.workspace().selected() || '')); }
  ngOnDestroy() {
    window.removeEventListener('resize', this.resizeListener);
    this.destroyed = true; cancelAnimationFrame(this.frame); this.observer?.disconnect(); this.themeObserver?.disconnect();
    for (const renderer of this.renderers.values()) { renderer.abort.abort(); renderer.terminal.dispose(); }
  }
}
