---
name: a20-review-english
tags: [activation, trigger, review, language-en]
runs: 3
max_turns: 8
allowed_tools: [Read, Glob, Grep, Skill]
---

Review a downstream service's Wow CommandGateway integration pull request against origin/main, report findings by severity, and do not edit files.
