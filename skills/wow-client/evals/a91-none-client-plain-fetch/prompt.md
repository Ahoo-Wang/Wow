---
name: a91-none-client-plain-fetch
tags: [activation, negative, non-wow-project]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

用 @ahoo-wang/fetcher 给一个普通 REST 用户接口加重试拦截器，这个后端不是 Wow 服务。
