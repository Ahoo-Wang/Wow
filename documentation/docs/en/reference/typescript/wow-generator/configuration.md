---
title: 'Generator configuration'
description: 'Generator configuration — @ahoo-wang/wow-generator'
---

# Generator configuration

The generator has two separate inputs: constructor/CLI execution options and an optional JSON/YAML customization file. A customization file cannot override inputPath, outputDir, logger, or tsconfig.

## Configuration contract

```json
{
  "apiClients": {
    "Items": { "ignorePathParameters": [] },
    "Orders": { "ignorePathParameters": ["tenantId"] }
  }
}
```

| Setting                                | Lookup and default                       | Effect                                                              |
| -------------------------------------- | ---------------------------------------- | ------------------------------------------------------------------- |
| `apiClients`                           | Missing → no per-tag overrides           | Keys are exact OpenAPI tag names                                    |
| `apiClients[tag].ignorePathParameters` | Missing/null → `['tenantId', 'ownerId']` | Ordinary API client positional path arguments omitted by exact name |
| Empty array                            | Explicit override                        | Keep every ordinary API path argument                               |
| Command-client path arguments          | Always `['tenantId', 'ownerId']`         | Per-tag API configuration does not change command clients           |

The array replaces the default; it does not append. Ignoring a parameter does not remove its URL template placeholder or supply a value. Supply it through request URL parameters, client defaults, or [CoSec attribution](https://fetcher.ahoo.me/reference/cosec/interceptors-and-attribution).

## Resolution and failures

`options.configPath ?? DEFAULT_CONFIG_PATH` selects the file; `DEFAULT_CONFIG_PATH` is `./fetcher-generator.config.json`. Paths are relative to process.cwd(), not to the input document. JSON/YAML format is inferred from content. Read/parse failure is logged and swallowed, restoring `{}`; the parsed shape is not validated, so structurally wrong content can fail later during generation. There is no environment-variable merging or automatic multi-file configuration hierarchy.

The internal interfaces named GeneratorOptions, Logger, GeneratorConfiguration and ApiClientConfiguration describe these shapes but are not root named exports. Infer the public constructor argument type as shown in [programmatic API](./programmatic-api).

## Complete application

Save the JSON above as `fetcher-generator.config.json`, ensure the spec uses the `Items` or `Orders` operation tags, then run:

```bash
pnpm exec wow-generator generate -i ./openapi.json -c ./fetcher-generator.config.json -o ./src/generated -t ./tsconfig.json
```

Inspect generated method signatures: Items retains tenantId/ownerId; Orders omits tenantId but keeps ownerId. The command does not run generated methods or connect to their API service.

## Implementation sources

[typescript/wow-generator/src/generateContext.ts:24](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/generateContext.ts#L24)

[typescript/wow-generator/src/types.ts:21](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/types.ts#L21)

[typescript/wow-generator/src/index.ts:35](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/index.ts#L35)
