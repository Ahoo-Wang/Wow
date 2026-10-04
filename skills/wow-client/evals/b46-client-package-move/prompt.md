---
name: b46-client-package-move
tags: [behavior, migration, react, package-rename]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

This React app depends on @ahoo-wang/fetcher-wow and @ahoo-wang/fetcher-react 5.0 and imports usePagedQuery from the @ahoo-wang/fetcher-react root. Move it to the Wow-owned packages.
