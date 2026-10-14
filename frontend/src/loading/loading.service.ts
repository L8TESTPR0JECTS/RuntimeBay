import { Injectable, OnDestroy, signal } from '@angular/core';
import { LoadingTasks, LoadingSnapshot } from './loading-tasks';
import { LoadingOverlay, mountLoadingOverlay } from './loading-overlay';

@Injectable({ providedIn: 'root' })
export class LoadingService implements OnDestroy {
  private state = signal<LoadingSnapshot>({ active: false, message: '', count: 0 });
  readonly snapshot = this.state.asReadonly();
  private overlay?: LoadingOverlay;
  private disposed = false;
  private tasks = new LoadingTasks(state => {
    if (this.disposed) return;
    this.state.set(state);
    if (state.active) {
      if (this.overlay) this.overlay.update(state.message);
      else this.overlay = mountLoadingOverlay(state.message);
    } else { this.overlay?.destroy(); this.overlay = undefined; }
  });
  get active() { return this.state().active; }
  scope() { return this.tasks.scope(); }
  ngOnDestroy() { this.disposed = true; this.overlay?.destroy(); this.overlay = undefined; }
}
