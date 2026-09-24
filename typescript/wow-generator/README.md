# `@ahoo-wang/wow-generator`

Generate TypeScript models, Fetcher decorator clients, and Wow clients from a
local or remote OpenAPI document.

## Install and run

```bash
pnpm add @ahoo-wang/fetcher @ahoo-wang/fetcher-decorator \
  @ahoo-wang/fetcher-eventstream @ahoo-wang/wow-client
pnpm add -D @ahoo-wang/wow-generator @ahoo-wang/fetcher-openapi typescript
pnpm exec wow-generator generate \
  --input ./openapi.yaml \
  --output ./src/generated \
  --ts-config-file-path ./tsconfig.json
```

The first line installs what the generated code imports at run time, the
second the generator and its peer `@ahoo-wang/fetcher-openapi`. The generator's
other peers (`fetcher`, `fetcher-decorator`, `fetcher-eventstream`,
`wow-client`) are the runtime packages of the first line; `wow-client` has to be
on the generator's minor version. Node 22.12 or later is required. The command
used to be `fetcher-generator`; that name stays as an alias until v10.

Generated clients are decorator classes, so the project that compiles them
needs `"experimentalDecorators": true` in its `tsconfig.json`. Released with
Wow 9.2.0, from the same tag and with the same version.

A successful run prints its warnings, if any, and one summary line:

```text
Generated 12 files into src/generated with /work/app/wow-generator.config.json, 1 warning
```

## Options and exit codes

| Option                             | Meaning                                                                   |
| ---------------------------------- | ------------------------------------------------------------------------- |
| `-i, --input <file>`               | OpenAPI 3.x document: a file path or an http(s) URL (required)            |
| `-o, --output <path>`              | Output directory, `src/generated` by default                              |
| `-c, --config <file>`              | Configuration file, `./wow-generator.config.json` by default              |
| `-t, --ts-config-file-path <file>` | The project's `tsconfig.json`                                             |
| `-H, --header <header>`            | `Name: value` header for an http(s) input or configuration; repeatable    |
| `--timeout <ms>`                   | Abandon an http(s) fetch after this many milliseconds, 30000 by default   |
| `--schema-docs <mode>`             | `summary` (default) or `full`, which also embeds each model's JSON schema |
| `--strict`                         | Exit with code 4 when the run logged a warning                            |
| `--verbose`                        | Log every step with timestamps, and the stack trace of a failure          |
| `--quiet`                          | Log only warnings and errors                                              |

An http(s) input fails on a response outside 2xx or when the timeout expires.
Swagger 2.0 documents are refused; convert them to OpenAPI 3 first.

| Exit code | Meaning                                                                                 |
| --------- | --------------------------------------------------------------------------------------- |
| 0         | Generated                                                                               |
| 1         | Internal error; rerun with `--verbose` for the stack trace                              |
| 2         | Input: unreadable, unfetchable, not OpenAPI 3.x, or an invalid option value             |
| 3         | Configuration: cannot be read, parsed or validated                                      |
| 4         | Specification: the document cannot be generated, or `--strict` and the run had warnings |
| 130       | Interrupted (Ctrl-C)                                                                    |

A failure prints one line naming what failed; `--verbose` adds the cause.

## Configuration

```json
{
  "apiClients": {
    "Catalog": {
      "ignorePathParameters": ["tenantId", "ownerId"],
      "methodNames": { "getCatalog_1": "getArchivedCatalog" }
    }
  }
}
```

The default config path is `./wow-generator.config.json`, resolved against
the working directory the CLI runs in. Only that default path is optional: when
no file is there the CLI generates with defaults. A
`./fetcher-generator.config.json`, the name the file had before, is still read
when the new name is absent, with a deprecation warning; rename it, as v10 no
longer reads it. A path passed to
`--config` has to exist, and a configuration that cannot be read, cannot be
parsed, or declares an option with the wrong shape fails the run rather than
degrading to defaults. Options the generator does not read - a misspelled
`apiClient`, say - are warned about by name and ignored.

The summary line names the absolute path of the configuration it read, so a
run answers "did my configuration take effect?" on its own. `--verbose` also
prints the settings it resolved to:

```text
[10:42:07] Configuration loaded from /work/app/wow-generator.config.json: apiClients=Catalog
```

If the summary names no configuration, the file never reached the generator -
check which directory the CLI ran in.

The generator records the files it wrote in `.wow-generator.json` at the root
of the output directory, so a later run removes the files it no longer
generates. Commit it with the output. A `.fetcher-generator.json` left by an
older version is read once and replaced.

`ignorePathParameters` defaults to `['tenantId', 'ownerId']` only in a Wow
document (one with `x-wow-context-alias` or aggregate routes); other documents
keep those path parameters. `methodNames` names the method of an operationId.

## Generated code

- Every file starts with `// Code generated by wow-generator. DO NOT EDIT.`.
  Relative imports end in `.js`, so the output compiles under `NodeNext` as
  well as `bundler`, and it is the same wherever it is written.
- A method is named by `methodNames`, else `x-fetcher-method`, else the last
  segment of the operationId camel-cased (`example.cart.add_cart_item` →
  `addCartItem`). Adding an operation never renames a method; two operations
  of one client with the same name fail with exit code 4.
- Command clients, and API clients of a document with `x-wow-context-alias`,
  merge the constructor's `apiMetadata` over their defaults:
  `new CartCommandClient({ fetcher })` keeps the bounded-context base path.
  Pass `basePath: ''` (query factories: `contextAlias: ''`) to reach a service
  directly.
- Query client factories type their fields as `` `${CartAggregatedFields}` ``;
  annotate a query as ``ListQuery<`${CartAggregatedFields}`>``.
- `--schema-docs full` embeds each model's JSON schema in its doc comment;
  the default is a summary.

## Property optionality

Every property a schema declares is generated as required. A statically typed
service has no absent `int` or `boolean` to hand back, so a model full of `?`
describes its exporter rather than its wire format - exporters routinely drop
properties that carry a default value from `required`, and the resulting nulls
checks are noise.

Optionality that the document genuinely means is carried where it belongs:

- **Null** stays in the type. A nullable property generates `T | null`, so a
  value that may be absent is still impossible to forget.
- **Commands** keep their declared optionality at the command type. A command
  client wraps its body in `PartialBy<Command, 'field' | ...>` built from the
  document's `required`, so a caller may still omit what the API says is
  optional - `AddCartItemCommand = CommandBody<PartialBy<AddCartItem,
'quantity'>>`. Requiring the model never narrows what a client may send.
- **API client bodies** are wrapped the same way: a JSON request body is
  `PartialBy<Model, 'field' | ...>` for the properties its schema leaves out of
  `required`.

One consequence is worth knowing: a non-nullable property that references its
own schema has no finite literal, since every level needs the next. A recursive
model that terminates declares its link nullable, which generates `T | null` and
constructs fine.

## Core capabilities

- Local JSON/YAML and HTTP(S) OpenAPI input.
- TypeScript models and decorator API clients grouped by tag, with typed path,
  query and header parameters and request bodies, required ones first.
- Wow bounded-context, command, snapshot, event, and query discovery.
- Recursive `index.ts` generation and ts-morph formatting.
- Programmatic `CodeGenerator` API with injectable logging, returning the files
  written and the warning count.

Regenerate after every contract change and compile the result before publishing.

## Documentation

- [Quick start: generate a client from a Wow service and call it](https://wow.ahoo.me/guide/typescript/quick-start)
- [Regenerate in CI](https://wow.ahoo.me/guide/typescript/regenerate-in-ci)
- [Generate a client from any OpenAPI document](https://wow.ahoo.me/guide/typescript/generated-client)
- [wow-generator reference](https://wow.ahoo.me/reference/typescript/wow-generator/)
- [Compatibility and versions](https://wow.ahoo.me/guide/typescript/compatibility)

[中文](./README.zh-CN.md) · [License](https://github.com/Ahoo-Wang/Wow/blob/main/LICENSE)
