---
title: 'wow-generator reference'
description: 'Generator reference — @ahoo-wang/wow-generator'
---

# wow-generator reference

Generate TypeScript models and decorator clients from an OpenAPI document, with optional Wow CQRS discovery. The only root symbols are CodeGenerator and DEFAULT_CONFIG_PATH.

## Install

```bash
pnpm add -D @ahoo-wang/wow-generator @ahoo-wang/fetcher @ahoo-wang/fetcher-eventstream @ahoo-wang/fetcher-decorator @ahoo-wang/fetcher-openapi @ahoo-wang/wow-client typescript
```

The package requires Node **>=22.12.0**; repository development additionally pins pnpm **10.34.5**. The command includes all recursive peers (wow-generator → wow-client/Decorator/EventStream/OpenAPI → Fetcher) and the compiler used below. ts-morph, commander and yaml install automatically as regular dependencies. These are generation-time dependencies; generated clients need their imported packages installed as runtime dependencies in the consuming application, as listed under [generated output](./generated-output.md).

The package version follows Wow, and `@ahoo-wang/wow-client` must be on the same minor version. Coming from `@ahoo-wang/fetcher-generator`? The command is now `wow-generator`, with `fetcher-generator` kept as an alias until v10, and generated code now imports `@ahoo-wang/wow-client` (and `@ahoo-wang/wow-client/legacy` for the `Condition` types of Wow 8.10 schemas), so regenerate existing output; see the [migration guide](../../../guide/typescript/migration.md).

## Minimal example

```bash
pnpm exec wow-generator generate -i ./openapi.json -o ./src/generated -t ./tsconfig.json
```

## Choose an entry point

Use the CLI for a reproducible build step and `CodeGenerator.generate()` for a Node script with a custom logger. Both produce source files; neither executes API operations. Start with the complete [CLI input and tsconfig](./cli.md), then check [output ownership](./generated-output.md) before regenerating.

## Topics

- [Generator CLI](cli)
- [Generator configuration](configuration)
- [Programmatic API](programmatic-api)
- [Generated output and regeneration](generated-output)
- [Wow aggregate discovery](wow-discovery)

[Complete public symbol index](./symbols.md)
