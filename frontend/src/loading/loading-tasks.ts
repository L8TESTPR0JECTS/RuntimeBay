export interface LoadingSnapshot { readonly active: boolean; readonly message: string; readonly count: number }
export type ReleaseLoading = () => void;

/** A task can only release its own ticket; overlapping work cannot unlock the UI early. */
export class LoadingTasks {
  private tickets = new Map<symbol, string>();
  constructor(private changed: (state: LoadingSnapshot) => void) {}
  begin(message: string): ReleaseLoading {
    const id = Symbol();
    this.tickets.set(id, message); this.publish();
    return () => { if (this.tickets.delete(id)) this.publish(); };
  }
  scope() { return new LoadingScope(this); }
  private publish() {
    this.changed({ active: this.tickets.size > 0, message: [...this.tickets.values()].at(-1) || '', count: this.tickets.size });
  }
}

/** Components release their outstanding tickets when they leave, even if a request is aborted. */
export class LoadingScope {
  private releases = new Set<ReleaseLoading>();
  private closed = false;
  constructor(private tasks: LoadingTasks) {}
  begin(message: string): ReleaseLoading {
    if (this.closed) return () => {};
    const end = this.tasks.begin(message);
    const release = () => { this.releases.delete(release); end(); };
    this.releases.add(release); return release;
  }
  async run<T>(message: string, work: () => Promise<T>): Promise<T> {
    if (this.closed) throw new Error('Loading scope is closed');
    const release = this.begin(message);
    try { return await work(); } finally { release(); }
  }
  close() { this.closed = true; for (const release of [...this.releases]) release(); }
}
