# Appearance

Runtime Bay offers twelve static palettes: Harbor, Ocean, Glacier, Forest, Sage, Amber, Copper, Clay, Rose, Plum, Violet, and Graphite. The first launch opens the picker. Use **Customize appearance**, the native **Appearance…** menu, or **⌘,** to reopen it.

Selections preview immediately and save automatically. Choose **Light**, **Dark**, or **System** independently of the palette. System follows macOS. The native title bar uses the same appearance choice as the workspace.

**Font size** adjusts the base text size from 12–20 px (14 px by default), with a live preview and a Reset button. Headings, labels, tables, service logs, and terminal text scale proportionally; sidebar widths, panel dimensions, spacing, and the logo retain their layout sizes. Longer text can wrap naturally. This preference is independent of native page zoom: ⌘+ / ⌘− continue to magnify the whole interface, and ⌘0 resets only zoom. Terminal canvases refit their columns and rows without restarting shells. CloudBeaver's embedded interface retains its own editor/font settings.

The saved `fontSize` is validated as an integer from 12 to 20. Older appearance files automatically use 14 px without resetting their palette, mode, or onboarding status; older clients saving four-field preferences preserve the stored font size.

Semantic tokens in `frontend/src/palettes.ts` style the sidebar, page surfaces, tables, dialogs, controls, and log viewer. Status colors retain their green/amber/red meaning. Palette tests check normal-text contrast for primary text, secondary text, buttons, sidebar, log output, and status messages in both appearances.

Preferences persist in `~/Library/Application Support/Runtime Bay/appearance.json`, separate from project definitions. They are served through the authenticated preferences API and written atomically with mode 0600. They therefore survive the local server's port changing on a later launch. Requests accept only known palette and appearance values. A malformed cosmetic file uses defaults without preventing the runtime engine from starting; its contents are preserved until the next explicit save.

The picker traps keyboard focus while open, supports Escape, and returns focus to its opener. Save failures appear in the picker with a retry action. Closing the picker acknowledges first-use setup; it does not change the selected palette or mode.

The supplied Runtime Bay logo appears in the sidebar at 61 × 63 points (about 65% larger than the original mark) and in the appearance preview. Loading uses a shared, full-window breathing overlay with a 300 px centerpiece. Its translucent backdrop blocks interaction while foreground work is pending. See the [loading contract](loading.md) for behavior, integration, motion, and accessibility details. Native packaging converts the same original artwork into a multi-resolution macOS app icon.
