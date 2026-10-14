# Shared loading overlay

Runtime Bay uses one loading overlay for foreground work. It covers the entire window, including the sidebar, open dialogs, and appearance drawer. The logo breathes; it never rotates.

## Visual contract

- The centerpiece is **300 × 300 CSS pixels**, scaled down only when the window cannot accommodate it. The sidebar mark remains 61 × 63 pixels. This is the large centerpiece requested for loading, rather than a literal 150-fold enlargement.
- The backdrop uses the current page color at **82% opacity**, with a soft blur so the workspace remains faintly visible.
- GSAP 3.15 animates scale from **0.94 to 1.06**, opacity from **0.72 to 1**, and an accent-to-highlight color blend. A separate halo breathes in sync.
- Each inhale lasts **1.65 seconds**; GSAP's yoyo repeat supplies the exhale, for a **3.3-second cycle**. There is no rotation and no CSS animation fallback that spins.
- Palette and appearance changes flow through semantic CSS variables. All code is bundled locally; startup needs no network connection or CDN.
- Reduce Motion renders a steady logo and halo, with no scale, opacity, or color animation.

## Responsibilities

| File | Responsibility |
| --- | --- |
| `frontend/src/loading/loading-tasks.ts` | Task ownership, concurrent request counting, idempotent release, and component scopes. No Angular or DOM dependency. |
| `frontend/src/loading/loading.service.ts` | Angular singleton exposing loading state and owning the one active overlay. |
| `frontend/src/loading/loading-overlay.ts` | Body-level overlay, accessible message, focus, keyboard blocking, inert content, scroll lock, and cleanup. |
| `frontend/src/loading/loading-motion.ts` | GSAP breathing effect and Reduce Motion handling, including animation cleanup. |
| `frontend/src/loading/loading.css` | Size, backdrop, logo mask, colors, and halo. |
| `frontend/src/loading/startup.ts` | Native startup entry point using the same renderer and animation. |
| `scripts/build-loading.mjs` | Bundles the standalone startup script from the shared implementation. |

## Using it

Each component creates a scope from the injected `LoadingService`. Wrap a finite foreground operation:

```ts
private loader = inject(LoadingService);
private loadingTasks = this.loader.scope();

async loadSomething() {
  return this.loadingTasks.run('Loading your workspace…', () => this.fetchSomething());
}

ngOnDestroy() {
  this.controller.abort();
  this.loadingTasks.close();
}
```

For an existing `try`/`catch`/`finally` operation, call `scope.begin(message)` and invoke the returned release function in `finally`. A task can release only its own ticket. Repeated release is harmless. Closing a component scope releases its remaining tickets without affecting another component's work.

Concurrent foreground tasks share one overlay. The newest pending task supplies its message; completing it restores the previous message if another task remains. The overlay is removed only after the last task releases. Errors and aborts release their tickets. Late completion after navigation cannot unlock a newer task. Loading never adds an artificial minimum wait.

## What blocks

Foreground startup, host discovery/selection, manual refresh, initial resource inspection, and explicit service/container mutations use the overlay. The mutation stays covered through its following refresh and dialog update. Container refresh requests are coalesced so a manual refresh can wait for an in-flight refresh rather than unlock immediately.

Routine project, log, container, and operation polling stays quiet. Appearance autosave also stays quiet to preserve live palette previews. Image pulls become background jobs after the submission is accepted, preserving their progress and Cancel controls. Cancelling a pull uses the overlay while the cancellation request is submitted.

## Interaction and accessibility

The renderer appends the overlay outside Angular's application root and marks the existing body content inert. Mouse, touch, keyboard shortcuts, and focus cannot reach the underlying workspace or dialogs. Scrolling is locked. The overlay is an accessible modal dialog with a live status message. It cannot be dismissed with Escape or a backdrop click while work is pending. Native macOS Close and Quit remain available.

Cleanup removes GSAP animations and event listeners, restores previous inert/scroll state, and returns focus to the previous element if it still exists. Native startup uses the same implementation until the real workspace replaces the startup document.

Run `npm run test:ui` for palette and loading ownership checks. Native/browser verification should include concurrent requests, failed requests with an open dialog, keyboard blocking, focus restoration, reduced motion, and the absence of overlays during periodic polling.
