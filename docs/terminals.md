# Workspace terminals

**＋ Terminal** sits below the project list; **⌘T** performs the same action. Each click creates and selects a new interactive `/bin/zsh -il` shell in the selected project's root, falling back to the user's home folder when no project is selected. Existing shell startup files and developer tools behave as they do in a login terminal. Runtime Bay does not source project files automatically.

Tabs appear beneath the projects in a shared scrolling sidebar. Their names are sequential and unique within the current engine lifetime. There is no application-imposed count limit; available macOS processes, file descriptors, and memory determine capacity. A failed launch shows an error and leaves existing tabs intact.

## Behavior

- Each tab has an independent PTY, working directory, shell environment, input queue, and scrollback. ANSI output, UTF-8, interactive prompts, foreground job control, Ctrl+C, Ctrl+D, and PTY window resizing are supported.
- Switching projects, Containers, Hosts, or terminals keeps shells running. Existing emulators remain mounted, retaining their scrollback and alternate screens.
- Reloading the web view reconnects to the engine's existing sessions. The engine retains the most recent 1 MiB of raw output per shell. On reconnect, output older than this buffer is discarded with a visible notice. The frontend retains up to 10,000 scrollback lines per opened emulator.
- Shell exit leaves the tab and its final output visible, with its exit code. Open a new terminal to continue.
- Closing a tab stops its shell and its foreground/background jobs. Closing Runtime Bay ends all its terminal sessions. Sessions and output are not saved to disk by Runtime Bay; the user's shell may save its normal history.
- Terminal input and background output do not trigger the global loading overlay. The create button briefly disables while a shell is being created; the terminal workspace opens immediately.
- macOS copy/paste shortcuts use the normal web-view clipboard actions. Ctrl shortcuts are delivered to the shell, including Ctrl+K. Cmd+K retains Runtime Bay's project import shortcut.

## Ownership and transport

`TerminalManager` owns the supervisor processes. `native/TerminalLauncher.c` calls `forkpty`, making the shell leader of a fresh session with a controlling terminal. Its stdin pipe is also its owner watchdog: EOF from Java triggers cleanup. Unlike configured service commands, interactive jobs can have different process groups; cleanup enumerates only processes whose live session ID matches this shell's captured session, sends TERM, then KILL. Processes that deliberately create another session to daemonize are outside this ownership model.

The Java/native control pipe uses one type byte, a big-endian uint32 payload length, then the payload. `I` sends at most 16 KiB of raw input; `R` contains two big-endian uint16 values (columns, rows). PTY output travels unchanged on stdout. Native input buffering is bounded and uses nonblocking PTY writes so a shell that stops reading cannot prevent owner-death cleanup.

All endpoints require the existing launch-specific Bearer token and Host/Origin checks:

| Endpoint | Behavior |
| --- | --- |
| `GET /api/terminals` | List live and exited tabs. |
| `POST /api/terminals` | Create a tab, optionally with `projectId`; no arbitrary launch command is accepted. |
| `DELETE /api/terminals/{id}` | Close the shell, clean up its jobs, and remove its tab. |
| `GET /api/terminals/{id}/output?after={byteOffset}` | Long-poll up to 10 seconds for base64-encoded bytes, next cursor, truncation indicator, and exit code. |
| `POST /api/terminals/{id}/input` | Send UTF-8 `data`, limited to 16 KiB per request. The frontend preserves ordering and splits large pastes on code-point boundaries. |
| `POST /api/terminals/{id}/resize` | Apply integer `columns` (2–1,000) and `rows` (1–1,000) through `TIOCSWINSZ`. |

The frontend serializes input per shell and bounds its pending paste queue to 1 MiB of characters. Output is raw bytes rather than line-oriented text, preserving split UTF-8 and escape sequences. Rendering waits for xterm's write callback before requesting more output. A quiet tab consumes only its bounded backend buffer until first opened. Runtime Bay's private `RUNTIMEBAY_*` environment values are removed before launching shells. Terminal output cannot automatically read or write the clipboard through OSC 52; no clipboard addon is installed.
