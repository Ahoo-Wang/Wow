# `@ahoo-wang/fetcher-generator`

Generate TypeScript models, Fetcher decorator clients, and Wow clients from a
local or remote OpenAPI document.

## Install and run

```bash
pnpm add -D @ahoo-wang/fetcher-generator
pnpm exec fetcher-generator generate \
  --input ./openapi.yaml \
  --output ./src/generated \
  --ts-config-file-path ./tsconfig.json
```

Generated clients import the matching Fetcher runtime packages. Add the peer
packages used by the generated output to application dependencies.

## Configuration

```json
{
  "apiClients": {
    "Catalog": {
      "ignorePathParameters": ["tenantId", "ownerId"]
    }
  }
}
```

The default config path is `./fetcher-generator.config.json`. When that optional
file is absent, the CLI logs the parse failure and continues with defaults.

## Core capabilities

- Local JSON/YAML and HTTP(S) OpenAPI input.
- TypeScript models and decorator API clients grouped by tag.
- Wow bounded-context, command, snapshot, event, and query discovery.
- Recursive `index.ts` generation and ts-morph formatting.
- Programmatic `CodeGenerator` API with injectable logging.

Regenerate after every contract change and compile the result before publishing.

## Documentation

- [OpenAPI generation recipe](https://fetcher.ahoo.me/recipes/openapi-client)
- [Generator reference](https://fetcher.ahoo.me/reference/generator)

[中文](./README.zh-CN.md) · [License](../../LICENSE)
