---
name: a93-client-auth
tags: [activation, trigger, auth]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

Wow 前端用生成的 CartCommandClient 调用 V10 服务，一直返回 401 wow.auth.unauthenticated，怎么让它带上登录用户的身份？
