---
title: 'wow-generator reference'
description: 'Generator reference — @ahoo-wang/wow-generator'
---

# wow-generator reference

Generate TypeScript models and decorator clients from an OpenAPI document, with optional Wow CQRS discovery. The package root exports CodeGenerator, its configuration default, loggers, errors and option types; see the [symbol index](./symbols.md).

## Install

```bash
pnpm add -D @ahoo-wang/wow-generator@~9.2.0 @ahoo-wang/fetcher @ahoo-wang/fetcher-eventstream @ahoo-wang/fetcher-decorator @ahoo-wang/fetcher-openapi @ahoo-wang/wow-client@~9.2.0 typescript
```

The package requires Node **>=22.12.0**; repository development additionally pins pnpm **10.34.5**. The command includes all recursive peers (wow-generator → wow-client/Decorator/EventStream/OpenAPI → Fetcher) and the compiler used below. ts-morph, commander and yaml install automatically as regular dependencies. These are generation-time dependencies; generated clients need their imported packages installed as runtime dependencies in the consuming application, as listed under [generated output](./generated-output.md).

The package version follows Wow, and `@ahoo-wang/wow-client` must be on the same minor version. Coming from `@ahoo-wang/fetcher-generator`? The command is now `wow-generator`, with `fetcher-generator` kept as an alias until v10; the configuration file is now `wow-generator.config.json`, with `fetcher-generator.config.json` still read with a deprecation warning until v10; and generated code now imports `@ahoo-wang/wow-client` (and `@ahoo-wang/wow-client/legacy` for the `Condition` types of Wow 8.10 schemas), so regenerate existing output; see the [migration guide](../../../guide/typescript/migration.md).

## Minimal example

```bash
pnpm exec wow-generator generate -i ./openapi.json -o ./src/generated -t ./tsconfig.json
```

## Choose an entry point

Use the CLI for a reproducible build step, with `--strict` in CI to fail on warnings, and `CodeGenerator.generate()` for a Node script with a custom logger. Both produce source files; neither executes API operations. Start with the complete [CLI input and tsconfig](./cli.md), then check [output ownership](./generated-output.md) before regenerating.

## Topics

- [Generator CLI](cli)
- [Generator configuration](configuration)
- [Programmatic API](programmatic-api)
- [Generated output and regeneration](generated-output)
- [Wow aggregate discovery](wow-discovery)

[Complete public symbol index](./symbols.md)
