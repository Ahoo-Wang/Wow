---
title: 'Generator CLI'
description: 'Generator CLI — @ahoo-wang/wow-generator'
---

# Generator CLI

`wow-generator generate` reads JSON/YAML and writes TypeScript models and decorator clients. The output does not depend on the packages installed where it runs, but the project that compiles it needs them; see [generated output](./generated-output).

## Options

| Option                             | Required/default              | Meaning                                                                                                  |
| ---------------------------------- | ----------------------------- | -------------------------------------------------------------------------------------------------------- |
| `-i, --input <file>`               | Required                      | Local path or HTTP/HTTPS URL of an OpenAPI 3.x document                                                  |
| `-o, --output <path>`              | `src/generated`               | Output root, resolved relative to working directory                                                      |
| `-c, --config <file>`              | `./wow-generator.config.json` | Generator configuration; see [configuration](./configuration). A named file has to exist                 |
| `-t, --ts-config-file-path <file>` | Omitted                       | ts-morph project configuration; supply your decorator-enabled tsconfig                                   |
| `-H, --header <header>`            | None; repeatable              | `Name: value` request header sent when the input or the configuration is an HTTP/HTTPS URL               |
| `--timeout <ms>`                   | `30000`                       | Milliseconds before fetching an HTTP/HTTPS input or configuration is abandoned                           |
| `--schema-docs <mode>`             | `summary`                     | What model doc comments carry: `summary` (title, description, constraints) or `full` (also the JSON schema) |
| `--strict`                         | Off                           | Exit with code 4 when the run logged a warning                                                           |
| `--verbose`                        | Off                           | Log every step with a timestamp, and the cause and stack trace of a failure                              |
| `--quiet`                          | Off                           | Log only warnings and errors                                                                             |
| `-v, --version`                    | Root command                  | Prints package version                                                                                   |
| `-h, --help`                       | Root/subcommand               | Commander help                                                                                           |

No watch, dry-run, framework, client-name or model-only switch is implemented.

A token-protected document is fetched with a header, for example from an environment variable:

```bash
pnpm exec wow-generator generate -i https://api.example.com/v3/api-docs \
  -H "Authorization: Bearer $API_TOKEN" --timeout 60000 -o ./src/generated -t ./tsconfig.json
```

## Output

By default the CLI prints warnings, errors and one summary line naming the file count, the output directory, the configuration it read and the number of warnings:

```text
Generated 3 files into ./src/generated with /work/app/wow-generator.config.json, 1 warning
```

`--verbose` prints every step with a timestamp; `--quiet` drops the summary. Lines carry symbols only on a terminal (TTY) without `NO_COLOR`, so CI logs and pipes stay plain.

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
pnpm add -D @ahoo-wang/wow-generator@~9.2.0 @ahoo-wang/fetcher-openapi typescript
pnpm exec wow-generator generate -i ./openapi.json -o ./src/generated -t ./tsconfig.json
pnpm exec tsc --noEmit -p ./tsconfig.json
```

For a repository fixture, after building, run from `typescript/wow-generator`:

```bash
node dist/cli.js generate -i "$PWD/test/demo.spec.json" -o /tmp/wow-demo-generated -t tsconfig.json
```

## Failures and exit codes

| Exit code | Kind          | When                                                                                                                                  |
| --------- | ------------- | ------------------------------------------------------------------------------------------------------------------------------------- |
| 0         | Success       | The files were written                                                                                                                |
| 1         | Internal      | An unexpected error, such as a write failure or an invalid tsconfig; rerun with `--verbose` for the stack trace                      |
| 2         | Input         | The input cannot be read or fetched, is neither JSON nor YAML, is not an OpenAPI 3.x document, or an option value is invalid         |
| 3         | Configuration | The configuration cannot be read, parsed or validated                                                                                 |
| 4         | Specification | The document describes code the generator cannot produce: a dangling `$ref`, two schemas that generate the same model, two operations that generate the same method, malformed Wow metadata; or `--strict` is set and the run logged a warning |
| 130       | Interrupted   | SIGINT (Ctrl-C)                                                                                                                       |

A failure prints one line that names the file or URL and what went wrong; `--verbose` adds the cause and the stack trace. Commander also rejects a missing required input. Parsing reads content rather than trusting the extension.

HTTP loading fails on a response outside 2xx (`HTTP 401 Unauthorized`), on a network error and when `--timeout` expires, instead of parsing an error page as a document. Any `http:` or `https:` URL is accepted, intranet addresses included: the CLI does not filter hosts, so pass only URLs you trust. A Swagger 2.0 document is refused with a hint to convert it (for example with swagger2openapi); an OpenAPI 3.1 document may omit `paths`.

Only an absent default configuration file is silent. A configuration named with `-c` that does not exist, or any configuration that cannot be read, parsed or validated, fails with exit code 3; see [configuration](./configuration). Check the warnings and [output ownership](./generated-output) before relying on a successful exit; `--strict` turns warnings into a failure for CI.

## Implementation sources

[typescript/wow-generator/src/cli.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/cli.ts)

[typescript/wow-generator/src/utils/clis.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/utils/clis.ts)

[typescript/wow-generator/src/utils/parsers.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/utils/parsers.ts)

[typescript/wow-generator/src/utils/resources.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/utils/resources.ts)
