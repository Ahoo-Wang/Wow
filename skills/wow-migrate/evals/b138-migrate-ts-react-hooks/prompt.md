---
name: b138-migrate-ts-react-hooks
tags: [behavior, typescript, react, migration]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

This React app depends on @ahoo-wang/fetcher-wow and @ahoo-wang/fetcher-react 5.0 and imports usePagedQuery from the @ahoo-wang/fetcher-react root to page order snapshots. The order service is now Wow V10. Move the app to the V10 packages. Do not modify files.
