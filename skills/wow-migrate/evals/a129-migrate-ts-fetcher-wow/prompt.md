---
name: a129-migrate-ts-fetcher-wow
tags: [activation, trigger, typescript, package-rename]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

Our order service moved to Wow V10. Replace @ahoo-wang/fetcher-wow in this Vite app with the V10 client and keep every command and query call working.
