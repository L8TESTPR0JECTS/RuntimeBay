# Third-party notices

Runtime Bay's own source is MIT licensed. The Java backend and Angular interface use third-party software with its own licenses:

| Component | License | Upstream |
| --- | --- | --- |
| Angular | MIT | https://github.com/angular/angular |
| RxJS | Apache-2.0 | https://github.com/ReactiveX/rxjs |
| GSAP 3.15.0 | [Standard “No Charge” GSAP License](https://gsap.com/standard-license/) | https://github.com/greensock/GSAP |
| tslib | 0BSD | https://github.com/microsoft/tslib |
| Gson | Apache-2.0 | https://github.com/google/gson |
| Error Prone annotations (Gson dependency) | Apache-2.0 | https://github.com/google/error-prone |
| OpenJDK runtime | GPL-2.0 with the Classpath Exception and additional bundled notices | https://openjdk.org/ |

Angular's build emits extracted frontend licenses. The macOS packaging script copies them to the application's Resources folder. The Apache 2.0 license for Gson (Copyright Google Inc.) and Error Prone annotations (Copyright The Error Prone Authors) is copied to `Resources/licenses/Apache-2.0.txt`. The jlink runtime retains its `legal` directory containing the licenses and notices for the Java modules and their dependencies.

JDK provenance depends on the JDK used to build the package. Check that runtime's own notices before publishing a release. Build and test tools (including Maven, TypeScript, and JUnit) are not part of the shipped application.

T3 Code, Bruno, Postman, Codex, and Claude remain integration candidates and are not bundled. Portainer's environment/container workflow informed this version; its source is not copied or bundled. Docker CLI is an optional user-installed executable with its own licensing.

GSAP is distributed under its own Standard “No Charge” license, not Runtime Bay’s MIT license. Its copyright and license headers are retained in both Angular’s extracted notices and the bundled native startup script. The license permits use subject to its terms and includes restrictions on competing visual animation builders; see the linked upstream license.
