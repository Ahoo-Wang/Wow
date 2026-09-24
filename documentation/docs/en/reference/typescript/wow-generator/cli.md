---
title: 'Generator CLI'
description: 'Generator CLI — @ahoo-wang/wow-generator'
---

# Generator CLI

`wow-generator generate` reads JSON/YAML and writes TypeScript models and decorator clients. Run it from a directory where your TypeScript configuration and installed Fetcher packages resolve.

## Options

| Option                             | Required/default                  | Meaning                                                                     |
| ---------------------------------- | --------------------------------- | --------------------------------------------------------------------------- |
| `-i, --input <file>`               | Required                          | Local path or HTTP/HTTPS URL                                                |
| `-o, --output <path>`              | `src/generated`                   | Output root, resolved relative to working directory                         |
| `-c, --config <file>`              | `./fetcher-generator.config.json` | Optional generator configuration; a failed read/parse is logged and ignored |
| `-t, --ts-config-file-path <file>` | Omitted                           | ts-morph project configuration; supply your decorator-enabled tsconfig      |
| `-v, --version`                    | Root command                      | Prints package version                                                      |
| `-h, --help`                       | Root/subcommand                   | Commander help                                                              |

No watch, dry-run, framework, client-name or model-only switch is implemented.

## Complete local run

Save this complete JSON document as `openapi.json`. It defines one GET operation and its response model; no service is contacted during generation.

```json
{
  "openapi": "3.0.3",
  "info": {
    "title": "Items",
    "version": "1.0.0"
  },
  "tags": [
    {
      "name": "Items"
    }
  ],
  "paths": {
    "/items/{id}": {
      "get": {
        "operationId": "getItem",
        "tags": ["Items"],
        "parameters": [
          {
            "name": "id",
            "in": "path",
            "required": true,
            "schema": {
              "type": "string"
            }
          }
        ],
        "responses": {
          "200": {
            "description": "Found",
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/Item"
                }
              }
            }
          }
        }
      }
    }
  },
  "components": {
    "schemas": {
      "Item": {
        "type": "object",
        "required": ["id"],
        "properties": {
          "id": {
            "type": "string"
          }
        }
      }
    }
  }
}
```

Save the following as `tsconfig.json`. Install the [runtime dependencies](./generated-output) before compiling the generated result. The TypeScript authoring example in [documents and operations](https://fetcher.ahoo.me/reference/openapi/documents-and-operations) is a separate format, not CLI JSON input.

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "ESNext",
    "moduleResolution": "bundler",
    "strict": true,
    "skipLibCheck": true,
    "experimentalDecorators": true,
    "emitDecoratorMetadata": true,
    "noEmit": true
  },
  "include": ["src/**/*.ts"]
}
```

```bash
pnpm add -D @ahoo-wang/wow-generator @ahoo-wang/fetcher-openapi typescript
pnpm exec wow-generator generate -i ./openapi.json -o ./src/generated -t ./tsconfig.json
pnpm exec tsc --noEmit -p ./tsconfig.json
```

For a repository fixture, after building, run from `typescript/wow-generator`:

```bash
node dist/cli.js generate -i "$PWD/test/demo.spec.json" -o /tmp/wow-demo-generated -t tsconfig.json
```

## Failures and lifecycle

The CLI exits 2 when its input precheck rejects a value, 1 for generation errors, and 130 on SIGINT. Commander also rejects missing required input. Parsing reads content rather than trusting the extension. A missing input, invalid JSON/YAML, unresolved component, invalid tsconfig or write failure rejects generation.

HTTP loading uses fetch then text without checking response.ok; an HTTP error body can fail later as a document. There is no CLI timeout/abort option. The CLI precheck blocks some private literal IP ranges but allows loopback and does not perform DNS/redirect validation; it is not a sandbox for untrusted URLs. Programmatic construction does not run that CLI precheck. Configuration read/parse errors are the exception: generation continues with `{}`. Inspect logs and [output ownership](./generated-output) before relying on a successful process exit.

## Implementation sources

[typescript/wow-generator/src/cli.ts:17](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/cli.ts#L17)

[typescript/wow-generator/src/utils/clis.ts:90](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/utils/clis.ts#L90)

[typescript/wow-generator/src/utils/parsers.ts:25](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/utils/parsers.ts#L25)

[typescript/wow-generator/src/utils/resources.ts:16](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/utils/resources.ts#L16)
