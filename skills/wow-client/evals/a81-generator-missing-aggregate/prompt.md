---
name: a81-generator-missing-aggregate
tags: [activation, trigger, aggregate-discovery]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

Our Wow service's OpenAPI spec only produces plain API clients from @ahoo-wang/wow-generator; the cart aggregate never gets a CartCommandClient. Explain which tags and operations the generator needs.
