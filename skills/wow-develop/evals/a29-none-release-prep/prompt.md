---
name: a29-none-release-prep
tags: [activation, negative, wow-repository, release-tooling]
runs: 3
max_turns: 8
allowed_tools: [Read, Glob, Grep, Skill]
---

准备 Wow 8.9.9 patch release：只同步 Gradle、package metadata 和版本文档，不修改 Wow framework 或 domain behavior。
