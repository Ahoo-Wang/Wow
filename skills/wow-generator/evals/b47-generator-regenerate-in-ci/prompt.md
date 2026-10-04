---
name: b47-generator-regenerate-in-ci
tags: [behavior, ci, regeneration]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

Our frontend commits the clients wow-generator produced from the orders service. Add a CI check that fails when someone forgets to regenerate after the service contract changes.
