# Appearance

Runtime Bay offers twelve static palettes: Harbor, Ocean, Glacier, Forest, Sage, Amber, Copper, Clay, Rose, Plum, Violet, and Graphite. The first launch opens the picker. Use **Customize appearance**, the native **Appearance…** menu, or **⌘,** to reopen it.

Selections preview immediately and save automatically. Choose **Light**, **Dark**, or **System** independently of the palette. System follows macOS. The native title bar uses the same appearance choice as the workspace.

Semantic tokens in `frontend/src/palettes.ts` style the sidebar, page surfaces, tables, dialogs, controls, and log viewer. Status colors retain their green/amber/red meaning. Palette tests check normal-text contrast for primary text, secondary text, buttons, sidebar, log output, and status messages in both appearances.

Preferences persist in `~/Library/Application Support/Runtime Bay/appearance.json`, separate from project definitions. They are served through the authenticated preferences API and written atomically with mode 0600. They therefore survive the local server's port changing on a later launch. Requests accept only known palette and appearance values. A malformed cosmetic file uses defaults without preventing the runtime engine from starting; its contents are preserved until the next explicit save.

The picker traps keyboard focus while open, supports Escape, and returns focus to its opener. Save failures appear in the picker with a retry action. Closing the picker acknowledges first-use setup; it does not change the selected palette or mode.
