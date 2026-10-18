# GitHub issue map

Live board: [Runtime Bay — Product Roadmap](https://github.com/orgs/L8TESTPR0JECTS/projects/4) · [All repository issues](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues).

The `RB-E##` and `RB-S##` identifiers are stable planning IDs used in the proposal and issue titles. GitHub issue numbers are the source of truth for live tracking. Stories are open and in **Backlog** pending refinement; priorities and milestones describe proposed order, not dates or commitments. Dependencies below are linked as native GitHub “blocked by” relationships.

## Epics

| Planning ID | GitHub issue | Title |
| --- | --- | --- |
| RB-E01 | [#1](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/1) | Application model and shared definitions |
| RB-E02 | [#2](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/2) | Persistent workflow cockpit |
| RB-E03 | [#3](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/3) | Distributed runtime operations |
| RB-E04 | [#4](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/4) | Credentials and environment protection |
| RB-E05 | [#5](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/5) | AI-assisted application setup |
| RB-E06 | [#6](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/6) | Protocol consoles and troubleshooting |
| RB-E07 | [#7](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/7) | Environment portability and beta delivery |

## Stories

| Planning ID | GitHub issue | Title | Priority | Milestone | Epic | Blocked by |
| --- | --- | --- | --- | --- | --- | --- |
| RB-S01 | [#8](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/8) | Introduce a workflow resource model | High | M1 | [RB-E01](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/1) | — |
| RB-S02 | [#9](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/9) | Bind resources per environment | High | M1 | [RB-E01](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/1) | [RB-S01](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/8) |
| RB-S03 | [#10](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/10) | Attach and reconcile existing application resources | High | M1 | [RB-E01](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/1) | [RB-S01](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/8), [RB-S02](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/9) |
| RB-S04 | [#11](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/11) | Build a multi-host workflow cockpit | High | M1 | [RB-E02](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/2) | [RB-S02](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/9), [RB-S03](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/10) |
| RB-S05 | [#12](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/12) | Restore workflow layout and operational context | High | M1 | [RB-E02](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/2) | [RB-S04](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/11) |
| RB-S06 | [#13](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/13) | Import and export a shareable workflow definition | High | M1 | [RB-E01](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/1) | [RB-S01](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/8), [RB-S02](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/9) |
| RB-S07 | [#14](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/14) | Manage credential references separately from workflows | High | M1 | [RB-E04](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/4) | [RB-S01](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/8) |
| RB-S08 | [#15](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/15) | Define the Go runtime worker and ownership contract | High | M2 | [RB-E03](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/3) | — |
| RB-S09 | [#16](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/16) | Build and verify host workers and runtime prerequisites | High | M2 | [RB-E03](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/3) | [RB-S02](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/9), [RB-S08](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/15) |
| RB-S10 | [#17](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/17) | Supervise saved services through the Go worker | High | M2 | [RB-E03](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/3) | [RB-S07](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/14), [RB-S08](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/15), [RB-S09](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/16) |
| RB-S11 | [#18](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/18) | Run dependency-aware plans across hosts | High | M2 | [RB-E03](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/3) | [RB-S03](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/10), [RB-S10](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/17) |
| RB-S12 | [#19](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/19) | Record run history and reconnect diagnostics | Medium | M2 | [RB-E03](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/3) | [RB-S10](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/17) |
| RB-S13 | [#20](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/20) | Make environment and target boundaries visible | High | M2 | [RB-E04](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/4) | [RB-S02](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/9), [RB-S07](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/14) |
| RB-S14 | [#21](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/21) | Add one AI provider through an adapter | High | M3 | [RB-E05](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/5) | [RB-S07](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/14) |
| RB-S15 | [#22](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/22) | Discover an existing application with AI assistance | High | M3 | [RB-E05](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/5) | [RB-S06](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/13), [RB-S14](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/21) |
| RB-S16 | [#23](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/23) | Review and apply AI-proposed workflow changes | High | M3 | [RB-E05](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/5) | [RB-S15](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/22) |
| RB-S17 | [#24](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/24) | Model a new application from a reviewed blueprint | Medium | M3 | [RB-E05](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/5) | [RB-S06](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/13), [RB-S14](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/21), [RB-S16](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/23) |
| RB-S18 | [#25](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/25) | Provide a workflow-aware Redis console | Medium | Later | [RB-E06](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/6) | [RB-S03](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/10), [RB-S04](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/11), [RB-S07](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/14) |
| RB-S19 | [#26](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/26) | Provide a workflow-aware Kafka console | Medium | Later | [RB-E06](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/6) | [RB-S03](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/10), [RB-S04](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/11), [RB-S07](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/14) |
| RB-S20 | [#27](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/27) | Correlate runtime events across workflow resources | Medium | Later | [RB-E06](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/6) | [RB-S11](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/18), [RB-S12](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/19) |
| RB-S21 | [#28](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/28) | Export a portable development-tool inventory | Low | Later | [RB-E07](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/7) | [RB-S06](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/13) |
| RB-S22 | [#29](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/29) | Validate and package the first distributed-workflow beta | High | Beta gate | [RB-E07](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/7) | [RB-S05](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/12), [RB-S06](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/13), [RB-S07](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/14), [RB-S10](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/17), [RB-S12](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/19), [RB-S13](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/20) |

## Milestones

| Milestone | Stories |
| --- | --- |
| M1 — Connected application cockpit | [RB-S01](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/8), [RB-S02](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/9), [RB-S03](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/10), [RB-S04](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/11), [RB-S05](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/12), [RB-S06](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/13), [RB-S07](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/14) |
| M2 — Reliable distributed runs | [RB-S08](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/15), [RB-S09](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/16), [RB-S10](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/17), [RB-S11](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/18), [RB-S12](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/19), [RB-S13](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/20) |
| M3 — AI-assisted setup | [RB-S14](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/21), [RB-S15](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/22), [RB-S16](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/23), [RB-S17](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/24) |
| Later — Protocols, diagnosis, portability | [RB-S18](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/25), [RB-S19](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/26), [RB-S20](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/27), [RB-S21](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/28) |
| Beta — Distributed workflow validation | [RB-S22](https://github.com/L8TESTPR0JECTS/RuntimeBay/issues/29) |

The [backlog proposal](backlog.md) remains the detailed product rationale and acceptance-criteria reference. The [CSV](backlog.csv) is a planning snapshot for offline review; it does not create Jira items or supersede GitHub status.
