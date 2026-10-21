---
name: b41-generator-cqrs
tags: [behavior, generation, cqrs]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

Our Wow V10 shop service commits its Manifest at `services/shop/manifest/baseline.json` (bounded context `shop`, aggregate `cart` with the commands `AddCartItem` and `RemoveCartItem`, snapshots on). Generate the TypeScript clients into `src/generated` and show how the app sends `AddCartItem` and pages the carts.
