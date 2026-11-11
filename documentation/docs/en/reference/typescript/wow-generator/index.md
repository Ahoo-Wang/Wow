---
title: 'wow-generator reference'
description: 'Generator reference — @ahoo-wang/wow-generator'
---

# wow-generator reference

Generate TypeScript from a Wow Manifest (`GET /wow/manifest`), the typed clients on `@ahoo-wang/wow-client`, or from an OpenAPI 3 document, its models and decorator API clients. The package root exports CodeGenerator, its configuration default, loggers, errors and option types; see the [symbol index](./symbols.md).

## Install

```bash
pnpm add -D @ahoo-wang/wow-generator @ahoo-wang/fetcher @ahoo-wang/fetcher-eventstream @ahoo-wang/fetcher-decorator @ahoo-wang/wow-client typescript
```

The package requires Node **>=22.12.0**; repository development additionally pins pnpm **10.34.9**. The command includes all recursive peers (wow-generator → wow-client/Decorator/EventStream → Fetcher) and the compiler used below. ts-morph, commander and yaml install automatically as regular dependencies. These are generation-time dependencies; generated clients need their imported packages installed as runtime dependencies in the consuming application, as listed under [generated output](./generated-output.md).

The package version follows Wow, and `@ahoo-wang/wow-client` must be on the same minor version. Coming from `@ahoo-wang/fetcher-generator`? The command is `wow-generator`, the configuration file `wow-generator.config.json`, and generated code imports `@ahoo-wang/wow-client`, so regenerate existing output; see the migration guide.

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

[Complete public symbol index](./symbols.md)
