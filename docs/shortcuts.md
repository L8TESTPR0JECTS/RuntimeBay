# Keyboard shortcuts

Runtime Bay registers shortcuts in its own macOS menu bar, only while the app is active. It installs no global keyboard shortcuts. Open File, Edit, View, or Window to discover the available commands.

| Shortcut | Action |
| --- | --- |
| ⌘+ or ⌘= | Zoom in |
| ⌘− | Zoom out |
| ⌘0 | Actual size (100%) |
| ⌘T | Open a terminal in the selected workflow |
| ⇧⌘[ / ⇧⌘] | Select previous / next terminal; wraps at either end |
| ⌘O | Open the workflow import dialog |
| ⌘K | Existing workflow import alias in the main workspace |
| ⌘, | Customize appearance |
| ⌘R | Reload the workspace |
| ⌘Z / ⇧⌘Z | Undo / redo in the focused editor |
| ⌘X / ⌘C / ⌘V / ⌘A | Cut / copy / paste / select all |
| ⌘M | Minimize window |
| ⌘H / ⌥⌘H | Hide Runtime Bay / hide other apps |
| ⌃⌘F | Toggle full screen |
| ⌘W | Close window (closing the last window quits Runtime Bay) |
| ⌘Q | Quit Runtime Bay |

Zoom changes the full web view, including embedded CloudBeaver, in steps of 10 percentage points from 50% to 200%. It is saved on this Mac using the native app's preferences and survives reloads and relaunches. Terminal canvases refit after zoom changes. macOS window chrome and the menu bar retain their system sizing.

Workflow import, terminal creation, and terminal switching are ignored while a blocking loader or app dialog is open. Switching terminals preserves their running shells. Control combinations (such as Ctrl+C, Ctrl+D, and Ctrl+K) remain available to the terminal. Option-modified typing and macOS shortcuts such as Spotlight and application switching are not captured. Embedded database editors retain their own contextual editing and search commands; the native menu handles app-wide commands even while a database frame has focus.

⌘R reloads the interface and can discard unsaved editor state. Resolve pending database edits and transactions before reloading or quitting. ⌘W closes the app window; it does not close an individual terminal tab.

When running the frontend in a regular browser, browser zoom and window commands remain browser-owned; workflow and terminal shortcuts apply to the main Runtime Bay page. Native app-wide routing inside embedded frames requires the macOS host.
