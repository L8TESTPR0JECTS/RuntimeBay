# Preserved third-party licenses

These files retain the licenses and attribution for dependencies shipped with Runtime Bay. The macOS packager copies this directory into `Contents/Resources/licenses/`. See [the full inventory](../THIRD_PARTY_NOTICES.md) for distribution boundaries and versions.

| File | Source at the audited version |
| --- | --- |
| `Angular-MIT.txt` | `@angular/core` 21.2.25, `LICENSE`; shared Angular license |
| `xterm-MIT.txt` | `@xterm/xterm` 6.0.0, `LICENSE` |
| `xterm-fit-MIT.txt` | `@xterm/addon-fit` 0.11.0, `LICENSE` |
| `RxJS-Apache-2.0.txt` | `rxjs` 7.8.2, `LICENSE.txt`, including its copyright attribution |
| `tslib-0BSD.txt` | `tslib` 2.8.1, `LICENSE.txt` |
| `Devicon-MIT.txt` | Devicon v2.17.0, `LICENSE`; also kept beside the original assets |
| `Gson-Apache-2.0.txt` | [Gson 2.13.2 LICENSE](https://raw.githubusercontent.com/google/gson/gson-parent-2.13.2/LICENSE) |
| `ErrorProne-Apache-2.0.txt` | [Error Prone 2.41.0 COPYING](https://raw.githubusercontent.com/google/error-prone/v2.41.0/COPYING) |
| `GSAP-NOTICE.txt` | GSAP 3.15.0, original notice in `dist/gsap.js` |
| `GSAP-Standard-License.txt` | Readable snapshot of [the upstream Standard license](https://gsap.com/community/standard-license/), retrieved 2026-10-16; custom terms, not MIT/Apache |

The selected JDK's module-specific legal files stay in the bundled `jre/legal/` directory. Packaging records its build metadata separately. CloudBeaver is downloaded as an optional container with its own upstream notices; it is not part of this directory's bundled-code inventory.

When upgrading a dependency, refresh its preserved text, the versions in the README and notice inventory, and the generated package notices. Keep the original copyright holders and license conditions intact. Runtime Bay's MIT license applies to its original source and does not replace these files.
