---
name: b43-generator-migrate-from-fetcher-generator
tags: [behavior, migration, package-rename]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

This frontend's package.json has a script "fetcher-generator generate -i http://localhost:8080/v3/api-docs -o src/generated" and depends on @ahoo-wang/fetcher-generator and @ahoo-wang/fetcher-wow. Move it to the Wow-owned packages without changing generated behavior.
