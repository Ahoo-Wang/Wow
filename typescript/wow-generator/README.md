# `@ahoo-wang/wow-generator`

Generate TypeScript models, Fetcher decorator clients, and Wow clients from a
local or remote OpenAPI document.

## Install and run

```bash
pnpm add -D @ahoo-wang/wow-generator
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

The default config path is `./fetcher-generator.config.json`, resolved against
the working directory the CLI runs in. Only that default path is optional: when
no file is there the CLI says so and generates with defaults. A path passed to
`--config` has to exist, and a configuration that cannot be read, cannot be
parsed, or declares an option with the wrong shape fails the run rather than
degrading to defaults. Options the generator does not read - a misspelled
`apiClient`, say - are warned about by name and ignored.

The log names the absolute path it read and the settings it resolved to, so a
run answers "did my configuration take effect?" on its own:

```text
ℹ️  Configuration loaded from /work/app/fetcher-generator.config.json: apiClients=Catalog
```

If that line is missing, the file never reached the generator - check which
directory the CLI ran in.

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

One consequence is worth knowing: a non-nullable property that references its
own schema has no finite literal, since every level needs the next. A recursive
model that terminates declares its link nullable, which generates `T | null` and
constructs fine.

## Core capabilities

- Local JSON/YAML and HTTP(S) OpenAPI input.
- TypeScript models and decorator API clients grouped by tag.
- Wow bounded-context, command, snapshot, event, and query discovery.
- Recursive `index.ts` generation and ts-morph formatting.
- Programmatic `CodeGenerator` API with injectable logging.

Regenerate after every contract change and compile the result before publishing.

## Documentation

- [OpenAPI generation recipe](https://fetcher.ahoo.me/guides/services/generated-client)
- [Generator reference](https://fetcher.ahoo.me/reference/generator)

[中文](./README.zh-CN.md) · [License](../../LICENSE)
