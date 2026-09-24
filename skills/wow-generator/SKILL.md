---
name: "wow-generator"
description: "Generate type-safe TypeScript clients from OpenAPI 3.x documents with the wow-generator CLI or CodeGenerator from @ahoo-wang/wow-generator, including models, plain API clients, and Wow CQRS command and query clients. Use for generator configuration, output structure, aggregate discovery rules, or moving from @ahoo-wang/fetcher-generator. Exclude hand-written runtime client code, Kotlin/Java Wow services, and developing the generator inside the Wow repository."
---

# wow-generator

## Workflow

1. Choose CLI generation (`wow-generator generate`) for project usage and `CodeGenerator` only for embedded tooling.
2. Resolve input, output, config, and tsconfig paths before generating.
3. Enable Wow CQRS generation only when bounded-context and aggregate metadata are present and intended.
4. Inspect generated structure and barrel exports after generation.
5. Load `references/api.md` for CLI flags, config shape, pipeline stages, and Wow CQRS generation rules.

## Packages

- Install `@ahoo-wang/wow-generator` as a dev dependency. Generated code imports `@ahoo-wang/wow-client`, `@ahoo-wang/fetcher`, and `@ahoo-wang/fetcher-decorator`, so the application installs those as runtime dependencies; `@ahoo-wang/fetcher-eventstream` and `@ahoo-wang/fetcher-openapi` keep their names and are peer dependencies.
- The CLI is `wow-generator generate …`. `fetcher-generator` remains an alias of the same binary until v10; new scripts use `wow-generator`.
- The configuration file is `wow-generator.config.json`. Until v10 a `fetcher-generator.config.json` is still read, with a deprecation warning, when the new name is absent; rename it. The ownership manifest is `.wow-generator.json`; an old `.fetcher-generator.json` is read once and replaced.
- The CLI exits 0 on success, 2 for input, 3 for configuration and 4 for specification problems (and with `--strict` when the run logged warnings); use `--strict` in CI.
- Moving from `@ahoo-wang/fetcher-generator`: swap the dev dependency, rename the script command and the configuration file, and regenerate so generated imports switch from `@ahoo-wang/fetcher-wow` to `@ahoo-wang/wow-client`.

## Key Practices

- Treat generated code as an output boundary; adjust generator config rather than hand-editing generated clients.
- Remember aggregates need a `{context}.{aggregate}` tag plus both `.snapshot_state.single` and `.snapshot.count` operations; an aggregate missing either is skipped with a warning, not turned into a plain API client.
- Generated command clients merge the constructor's `apiMetadata` over their defaults, so `new CartCommandClient({ fetcher })` keeps the bounded-context base path; pass `basePath: ''` (query factories: `contextAlias: ''`) to reach a service directly.
- Method names come from `apiClients[tag].methodNames`, `x-fetcher-method`, or the last segment of the operationId; resolve a name clash (exit 4) through `methodNames`.
- Keep API client tag exclusion and CQRS generation rules aligned so duplicate clients are not emitted.
- Confirm generator behavior against the installed version, or the Wow repository sources under `typescript/wow-generator/src` at the matching release, before promising an output shape.

## References

- `references/api.md`: Detailed package API, examples, and edge-case guidance. Load it only when the task needs CLI commands, CodeGenerator usage, configuration fields, output structure, pipeline stages, and Wow CQRS generation examples.

## Related Skills

- $wow-client: Use for runtime Wow command and query client behavior and React query hooks.
- Raw OpenAPI TypeScript modeling and the Fetcher runtime are documented by the fetcher project skills.
