---
id: kno-01kqe94cgmd2
title: Package knot-prime Pi extension for third-party installation
status: open
type: task
priority: 3
mode: hitl
created: '2026-04-30T04:07:41.844334511Z'
updated: '2026-05-05T01:38:54.088449090Z'
links:
- kno-01kqcpb0t5s7
- kno-01kqgq9vhmvr
acceptance:
- title: There is a supported way for an external project to install and use the `knot-prime` Pi extension.
  done: false
- title: The installation and configuration steps are documented.
  done: false
- title: The packaging/install flow is validated from a project outside this repo.
  done: false
---

## Description

The repo already has a Pi coding extension in `knot-prime.ts` that injects `knot prime` output into agent context.

We should turn that into something third-party projects can install and use without copying the file out of this repo by hand. The work likely includes choosing the packaging/distribution mechanism, making the extension consumable outside this repo, and documenting the install/use flow.
