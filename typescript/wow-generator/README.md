# `@ahoo-wang/wow-generator`

Generate the typed TypeScript clients of a Wow service from its Manifest, and
TypeScript models and Fetcher decorator clients from any OpenAPI 3 document.

## From a Wow Manifest

A Wow service describes its bounded context, aggregates, commands, events and
state in its Manifest (`GET /wow/manifest`, or the `manifest/baseline.json` a
service commits). The generator turns it into one client per aggregate on
`@ahoo-wang/wow-client`:

```bash
pnpm add @ahoo-wang/fetcher @ahoo-wang/fetcher-eventstream @ahoo-wang/wow-client
pnpm add -D @ahoo-wang/wow-generator typescript
curl -s http://localhost:8080/wow/manifest > manifest.json
pnpm exec wow-generator generate \
  --input ./manifest.json \
  --output ./src/generated \
  --ts-config-file-path ./tsconfig.json
```

For each bounded context it writes `types.ts` (the command, event and state
types of the Manifest's schemas) and `boundedContext.ts` (its name, error codes
and wait hops), and for each aggregate:

- `aggregate.ts`: the aggregate's descriptor, its state fields and its event
  union;
- `commandClient.ts`: `<Aggregate>CommandClient`, a method per command and a
  `…Stream` twin that answers one result per hop of the wait;
- `queryClient.ts`: `<Aggregate>SnapshotQueryClient`,
  `<Aggregate>EventQueryClient` and `<Aggregate>StateClient`.

<!-- typecheck-generated: example/eshop/web/src/generated/basket -->

```ts
import { Fetcher } from '@ahoo-wang/fetcher';
import { filter } from '@ahoo-wang/wow-client/dsl';
import {
  BasketCommandClient,
  BasketSnapshotQueryClient,
} from './generated/index.js';

const fetcher = new Fetcher({ baseURL: 'http://localhost:8080' });
const basket = new BasketCommandClient({ fetcher });
const baskets = new BasketSnapshotQueryClient({ fetcher });

await basket.addBasketItem(
  { productId: 'p-1', quantity: 2, unitPrice: 10 },
  { id: 'buyer-1' },
);
const page = await baskets.paged({ filter: filter.matchAll() });
console.log(page.total);
```

`--strict` fails a run that logged a warning, which a CI step that regenerates
the clients from a service's committed Manifest wants.

## From an OpenAPI document

Any OpenAPI 3 document, a service's own routes or a third-party API, generates
a model per component schema and a Fetcher decorator client per tag:

```bash
pnpm add @ahoo-wang/fetcher @ahoo-wang/fetcher-decorator \
  @ahoo-wang/fetcher-eventstream
pnpm add -D @ahoo-wang/wow-generator typescript
pnpm exec wow-generator generate \
  --input ./openapi.yaml \
  --output ./src/generated \
  --ts-config-file-path ./tsconfig.json
```

Generated API clients are decorator classes, so the project that compiles them
needs `"experimentalDecorators": true` in its `tsconfig.json`. The document is
read as OpenAPI alone: schema keys, tags and path parameters mean what they say
in it, with no Wow conventions on top.

## Versions

The version follows Wow, so a minor release may contain breaking changes: keep the Wow packages on one minor with `save-prefix=~` or `--save-exact`, as [version ranges](https://wow.ahoo.me/guide/typescript/compatibility#version-ranges) explains. `wow-client` has to be on the generator's minor version. Node 22.12 or later is required, and TypeScript 6 or later: CI tests 6.0 through the latest 7.x.

A successful run prints its warnings, if any, and one summary line:

```text
Generated 12 files into src/generated with /work/app/wow-generator.config.json, 1 warning
```

## Options and exit codes

| Option                             | Meaning                                                                     |
| ---------------------------------- | --------------------------------------------------------------------------- |
| `-i, --input <file>`               | Wow Manifest or OpenAPI 3.x document: a file path or http(s) URL (required) |
| `-o, --output <path>`              | Output directory, `src/generated` by default                                |
| `-c, --config <file>`              | Configuration file, `./wow-generator.config.json` by default                |
| `-t, --ts-config-file-path <file>` | The project's `tsconfig.json`                                               |
| `-H, --header <header>`            | `Name: value` header for an http(s) input or configuration; repeatable      |
| `--timeout <ms>`                   | Abandon an http(s) fetch after this many milliseconds, 30000 by default     |
| `--schema-docs <mode>`             | `summary` (default) or `full`, which also embeds each model's JSON schema   |
| `--strict`                         | Exit with code 4 when the run logged a warning                              |
| `--verbose`                        | Log every step with timestamps, and the stack trace of a failure            |
| `--quiet`                          | Log only warnings and errors                                                |

An http(s) input fails on a response outside 2xx or when the timeout expires.
Swagger 2.0 documents are refused; convert them to OpenAPI 3 first.

| Exit code | Meaning                                                                                                                    |
| --------- | -------------------------------------------------------------------------------------------------------------------------- |
| 0         | Generated; or `--help`, `help` or `--version` printed                                                                      |
| 1         | Internal error; rerun with `--verbose` for the stack trace                                                                 |
| 2         | Input or usage: unreadable, unfetchable, not OpenAPI 3.x; or an invalid or missing option, an unknown option or command    |
| 3         | Configuration: cannot be read, parsed or validated                                                                         |
| 4         | Specification: the document cannot be generated, or `--strict` and the run had warnings                                    |
| 5         | Output: the manifest cannot be parsed or a newer generator wrote it, a path escapes the output, or a write or delete fails |
| 130       | Interrupted (Ctrl-C)                                                                                                       |

A failure prints one line naming what failed; `--verbose` adds the cause.

## Configuration

The configuration applies to the API clients of an OpenAPI document:

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
no file is there the CLI generates with defaults. A path passed to
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
generates. Commit it with the output.

`ignorePathParameters` names the path parameters a tag's client leaves out,
because an interceptor fills them; none by default. `methodNames` names the
method of an operationId.

## Generated code

- Every file starts with `// Code generated by wow-generator. DO NOT EDIT.`.
  Relative imports end in `.js`, so the output compiles under `NodeNext` as
  well as `bundler`, and it is the same wherever it is written.
- An API method is named by `methodNames`, else `x-fetcher-method`, else the
  last segment of the operationId camel-cased (`catalog.get_item` →
  `getItem`). Adding an operation never renames a method; two operations of
  one client with the same name fail with exit code 4.
- `--schema-docs full` embeds each model's JSON schema in its doc comment;
  the default is a summary.

## Property optionality

Every property an OpenAPI schema declares is generated as required. A statically typed
service has no absent `int` or `boolean` to hand back, so a model full of `?`
describes its exporter rather than its wire format - exporters routinely drop
properties that carry a default value from `required`, and the resulting nulls
checks are noise.

Optionality that the document genuinely means is carried where it belongs:

- **Null** stays in the type. A nullable property generates `T | null`, so a
  value that may be absent is still impossible to forget.
- **API client bodies** are wrapped the same way: a JSON request body is
  `PartialBy<Model, 'field' | ...>` for the properties its schema leaves out of
  `required`.

One consequence is worth knowing: a non-nullable property that references its
own schema has no finite literal, since every level needs the next. A recursive
model that terminates declares its link nullable, which generates `T | null` and
constructs fine.

## Core capabilities

- Wow Manifest input: the typed command, query and state clients of every
  aggregate on `@ahoo-wang/wow-client`.
- Local JSON/YAML and HTTP(S) OpenAPI input: TypeScript models and decorator
  API clients grouped by tag, with typed path, query and header parameters and
  request bodies, required ones first.
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
