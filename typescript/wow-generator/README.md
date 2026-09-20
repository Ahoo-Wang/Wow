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
  },
  "readModel": {
    "nonNullRequired": false
  }
}
```

The default config path is `./fetcher-generator.config.json`. When that optional
file is absent, the CLI logs the parse failure and continues with defaults.

### `readModel.nonNullRequired`

A property a document leaves out of `required` is generated as optional. That is
what the request side means, but exporters commonly drop properties that carry a
default value from `required`, which understates a response the server always
populates.

Enable `readModel.nonNullRequired` to generate every non-nullable property of an
aggregate state or domain event schema as required. The rule is deliberately
narrow:

- Nullable properties stay optional, in every spelling of null - the OpenAPI 3.0
  `nullable` flag, a `null` entry in a 3.1 type array, a `null` enum member or
  const, one `anyOf` branch that admits null, exactly one such `oneOf` branch,
  and an `allOf` whose every branch does. Every keyword must agree: a `null`
  enum member alongside `type: string` is rejected by that sibling type, and a
  `not` whose subschema accepts null rejects it too, so those properties are
  required rather than optional.
- `writeOnly` properties stay optional, including when the flag sits on the
  referenced component. They belong to the request side, so a response may omit
  them however their type reads.
- Request schemas are untouched, and that means every operation's body and
  parameters, not only Wow commands. Over-stating a request's required
  properties would reject a call the client is entitled to make.
- A schema both a request and a read model reach keeps its declared shape. Those
  that the rule would have changed are listed in the generation log.

Defaults to `false`, which generates exactly what the document declares. Only
turn it on when the service really does serialise every non-null property -
Jackson's `NON_NULL` inclusion does, `NON_DEFAULT` does not.

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
