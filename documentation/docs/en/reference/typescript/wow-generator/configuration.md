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
    "Orders": {
      "ignorePathParameters": ["tenantId"],
      "methodNames": { "listOrders_1": "listArchivedOrders" }
    }
  }
}
```

| Setting                                | Lookup and default                       | Effect                                                              |
| -------------------------------------- | ---------------------------------------- | ------------------------------------------------------------------- |
| `apiClients`                           | Missing → no per-tag overrides           | Keys are exact OpenAPI tag names                                    |
| `apiClients[tag].ignorePathParameters` | Missing → `['tenantId', 'ownerId']` in a Wow document, `[]` otherwise | Ordinary API client positional path arguments omitted by exact name |
| Empty array                            | Explicit override                        | Keep every ordinary API path argument                               |
| `apiClients[tag].methodNames`          | Missing → names derived from operationIds | Object mapping an operationId to its method name; must be a valid identifier |
| Command-client path arguments          | Always `['tenantId', 'ownerId']`         | Per-tag API configuration does not change command clients           |

A Wow document is one with `info['x-wow-context-alias']` or with Wow aggregate routes; there Wow's CoSec interceptor fills `tenantId` and `ownerId`. Any other document keeps those path parameters unless the configuration says otherwise.

`methodNames` takes precedence over the operation's `x-fetcher-method` extension and over the name derived from the operationId (see [methods](./generated-output#methods)). Use it when two operations of one tag derive the same name, which otherwise fails the run with exit code 4.

The array replaces the default; it does not append. Ignoring a parameter does not remove its URL template placeholder or supply a value. Supply it through request URL parameters, client defaults, or [CoSec attribution](https://fetcher.ahoo.me/reference/cosec/interceptors-and-attribution).

## Resolution and failures

`options.configPath` (CLI `-c`) selects the file, which may also be an HTTP/HTTPS URL fetched with the same headers and timeout as the input. Without it the generator reads `DEFAULT_CONFIG_PATH`, `./wow-generator.config.json`, and when that is absent the pre-Wow name `./fetcher-generator.config.json` with a deprecation warning; v10 no longer reads the old name. Paths are relative to process.cwd(), not to the input document. JSON/YAML format is inferred from content.

| Situation                                                       | Result                                                |
| --------------------------------------------------------------- | ----------------------------------------------------- |
| No file at the default path (nor at the old name)               | Generates with defaults                               |
| Empty file                                                      | Generates with defaults, with a warning               |
| A file named with `configPath`/`-c` does not exist              | Fails: `GeneratorError` `configuration`, exit code 3  |
| The file cannot be read, is neither JSON nor YAML               | Fails, exit code 3                                    |
| A block or option has the wrong shape (`apiClients` not an object, `ignorePathParameters` not an array of strings, a `methodNames` value that is not a valid method name) | Fails, exit code 3 |
| An unknown key, such as a misspelt `apiClient`                  | Warning naming the key; the key is ignored            |

The CLI summary names the configuration it read, and `--verbose` logs the settings it resolved to (`apiClients=Items, Orders`). There is no environment-variable merging or automatic multi-file configuration hierarchy.

`GeneratorConfiguration` and `ApiClientConfiguration` are exported from the package root as types, so a script can type a configuration it builds; see [programmatic API](./programmatic-api).

## Complete application

Save the JSON above as `wow-generator.config.json`, ensure the spec uses the `Items` or `Orders` operation tags, then run:

```bash
pnpm exec wow-generator generate -i ./openapi.json -c ./wow-generator.config.json -o ./src/generated -t ./tsconfig.json
```

Inspect generated method signatures: Items retains tenantId/ownerId; Orders omits tenantId but keeps ownerId. The command does not run generated methods or connect to their API service.

## Implementation sources

[typescript/wow-generator/src/input/configuration.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/input/configuration.ts)

[typescript/wow-generator/src/analysis/apiClients.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/analysis/apiClients.ts)

[typescript/wow-generator/src/api/configuration.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/api/configuration.ts)

[typescript/wow-generator/src/pipeline/codeGenerator.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/pipeline/codeGenerator.ts)
