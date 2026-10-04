---
name: a93-client-auth
tags: [activation, trigger, auth]
runs: 3
max_turns: 8
allowed_tools: [Read, Glob, Grep, Skill]
---

Wow 前端用生成的 CartCommandClient 调用接口，一直报 Missing required path parameter: ownerId，怎么让它从登录用户带上？
