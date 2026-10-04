# Wow Generator Reference

`@ahoo-wang/wow-generator` (formerly `@ahoo-wang/fetcher-generator`) turns an OpenAPI 3.x document into TypeScript models, plain API clients and Wow CQRS command and query clients. Full reference: https://wow.ahoo.me/reference/typescript/wow-generator/ (CLI, configuration, Wow discovery, generated output, programmatic API); guides: https://wow.ahoo.me/guide/typescript/generated-client.html and https://wow.ahoo.me/guide/typescript/regenerate-in-ci.html. Source: `typescript/wow-generator/src`; the package README in the Wow repository details how schemas become types.

## Contents

- [Install and migrate](#install-and-migrate)
- [CLI](#cli)
- [CodeGenerator](#codegenerator)
- [Configuration](#configuration)
- [Output](#output)
- [Wow CQRS discovery](#wow-cqrs-discovery)
- [Generated clients](#generated-clients)

## Install and migrate

```bash
pnpm add -D @ahoo-wang/wow-generator typescript
pnpm add @ahoo-wang/wow-client @ahoo-wang/fetcher @ahoo-wang/fetcher-decorator @ahoo-wang/fetcher-eventstream
```

- Peers: `@ahoo-wang/fetcher`, `@ahoo-wang/fetcher-decorator`, `@ahoo-wang/fetcher-eventstream` and `@ahoo-wang/wow-client` (same minor version) — what generated code imports at run time, so the application installs them as runtime dependencies. `@ahoo-wang/fetcher-openapi` is not needed. Node `>=22.12.0`; TypeScript 6 or later (CI tests 6.0 through the latest 7.x); the compiling project needs `experimentalDecorators`.
- From `@ahoo-wang/fetcher-generator`: swap the dev dependency, change scripts from `fetcher-generator generate` to `wow-generator generate` (the old command stays an alias of the same binary until v10), rename `fetcher-generator.config.json` to `wow-generator.config.json` (until v10 the old name is still read, with a deprecation warning, when the new one is absent), and regenerate so generated imports point at `@ahoo-wang/wow-client` instead of `@ahoo-wang/fetcher-wow`. The manifest `.fetcher-generator.json` is read once by the next run and replaced by `.wow-generator.json`.

## CLI

```bash
npx wow-generator generate -i ./openapi.yaml -o ./src/generated -c ./wow-generator.config.json -t ./tsconfig.json
# Protected document, failing on warnings in CI
npx wow-generator generate -i https://api.example.com/v3/api-docs -H "Authorization: Bearer $TOKEN" --strict -o ./src/generated
```

| Flag | Meaning | Default |
|---|---|---|
| `-i, --input <file>` | OpenAPI 3.x file (JSON/YAML) or HTTP/HTTPS URL | required |
| `-o, --output <path>` | Output directory | `src/generated` |
| `-c, --config <file>` | Configuration file; a named file has to exist | `./wow-generator.config.json` |
| `-t, --ts-config-file-path <file>` | TypeScript config | — |
| `-H, --header <header>` | `Name: value` for an HTTP(S) input or config; repeatable | — |
| `--timeout <ms>` | Abandon an HTTP(S) fetch | `30000` |
| `--schema-docs <mode>` | Model doc comments: `summary`, or `full` to embed the JSON schema | `summary` |
| `--strict` | Exit 4 when the run logged a warning | off |
| `--verbose` / `--quiet` | Every step with timestamps and a failure's stack / only warnings and errors | off |

Exit codes: 0 success, 1 internal error, 2 input (unreadable, unfetchable or non-2xx, not OpenAPI 3.x — Swagger 2.0 is refused — or an invalid option value), 3 configuration (cannot be read, parsed or validated), 4 specification (and `--strict` with warnings), 130 SIGINT. A failure prints one line. The default log is the warnings plus one summary line (`Generated N files into DIR with CONFIG, K warnings`). Any http(s) URL is accepted, intranet hosts included. In CI, generate with `--strict` into a directory only the generator owns, commit the `.wow-generator.json` manifest, fail when the output differs from what is committed, then type-check.

## CodeGenerator

ESM and CommonJS (`const { CodeGenerator } = require('@ahoo-wang/wow-generator')`).

```typescript
import { CodeGenerator, ConsoleLogger, GeneratorError } from '@ahoo-wang/wow-generator';

const generator = new CodeGenerator({
  inputPath: './openapi.yaml',
  outputDir: './src/generated',
  tsConfigFilePath: './tsconfig.json',
  logger: new ConsoleLogger({ level: 'quiet' }), // 'quiet' | 'normal' | 'verbose'
});
try {
  const { files, warnings } = await generator.generate();
} catch (error) {
  process.exitCode = error instanceof GeneratorError ? error.exitCode : 1;
}
```

- `GeneratorOptions`: `inputPath`, `outputDir`, `configPath?`, `tsConfigFilePath?`, `logger?` (default `new ConsoleLogger()` at `normal`), `headers?`, `timeoutMs?`, `schemaDocs?`. It does not extend ts-morph `ProjectOptions`. `generate()` resolves to `{ files, configPath?, warnings }` and rejects with a `GeneratorError` (`kind`: `input` | `configuration` | `specification`, plus `exitCode`).
- A custom `Logger` implements `info`/`success`/`error`/`progress`/`progressWithCount`; `warn` is optional and falls back to `info`.
- Exports: `CodeGenerator`, `DEFAULT_CONFIG_PATH`, `ConsoleLogger`, `SilentLogger`, `GeneratorError`, `EXIT_CODES`, and the types `GeneratorOptions`, `GenerationResult`, `GeneratorConfiguration`, `ApiClientConfiguration`, `Logger`, `ConsoleLoggerOptions`, `LogLevel`, `GeneratorErrorKind`. `generateIndex` and `optimizeSourceFiles` are private.
- Ownership: each successful run records generated files and content hashes (barrel indexes included) in `.wow-generator.json` at the output root. Later runs remove files no longer generated only while they are unchanged, then rebuild indexes; handwritten files, modified stale files and legacy output without an ownership record are kept, so keep the manifest with the output. Re-running with the output inside the tsconfig rebuilds declarations instead of appending duplicates. Rendering failures leave existing output intact; a filesystem save failure can leave partial output (generation is not a directory transaction).

## Configuration

```json
{ "apiClients": { "TagName": { "ignorePathParameters": ["tenantId", "ownerId"], "methodNames": { "getUser_1": "getUser" } } } }
```

- `apiClients`: tag name → API client configuration. `ignorePathParameters` drops path parameters from generated **API client** methods (default `['tenantId', 'ownerId']` for a Wow document — one with `x-wow-context-alias` or aggregate routes — and `[]` otherwise); command clients always drop `tenantId`/`ownerId`. `methodNames` maps an operationId to its method name and wins over `x-fetcher-method` and the derived name; use it when two operations of a tag derive the same name, which otherwise fails with exit 4.
- `wow-generator.config.json` is the only configuration file (there is no `.fetcherrc.json`). The default path is optional and resolved against the working directory; pass `-c` when it lives elsewhere. A `--config` path that does not exist, content that will not parse and an option with the wrong shape each exit 3; unknown keys warn by name. The summary line names the absolute path read and `--verbose` logs the resolved settings, so a silent no-op (wrong working directory, stale binary) shows up in one run.

## Output

```
output/
├── index.ts                    # barrel at every directory level
├── {context-alias}/
│   ├── boundedContext.ts       # e.g. EXAMPLE_BOUNDED_CONTEXT_ALIAS
│   ├── types.ts                # models for this schema path
│   ├── {tag}ApiClient.ts       # one per non-CQRS tag, camelCase (cartApiClient.ts)
│   └── {aggregate}/
│       ├── commandClient.ts    # CommandClient + StreamCommandClient + CommandEndpointPaths
│       └── queryClient.ts      # QueryClientFactory + DomainEventType + DomainEventTypeMapTitle
└── {other-schema-path}/types.ts
```

- Models are named by schema path prefix: `ai.AiMessage.Assistant` → `ai/types.ts`, type `AiMessageAssistant`. `wow.*` schemas are skipped except `wow.api.query.*PagedList` and `wow.api.query.Operator*Map` (the base `wow.api.query.PagedList` is skipped too; aggregate `*PagedList` schemas map to `PagedList` from `@ahoo-wang/wow-client`). Aggregate event-stream and materialized-snapshot cursor-page wrappers are skipped like their paged-list wrappers; business cursor-page models are generated.
- Every file starts with `// Code generated by wow-generator. DO NOT EDIT.`, is formatted with organized, type-only imports, and is checked to declare or import every name it uses exactly once before anything is saved. Imports are explicit (no `fixMissingImports`), relative imports end in `.js` and barrels re-export `./dir/index.js`, so output compiles under NodeNext and is the same whether or not the output directory resolves `@ahoo-wang/*`.
- Every declared model property is generated **required**; optionality the document means lives elsewhere: a nullable value is `T | null`, an omittable command field is `CommandBody<PartialBy<Command, 'field' | …>>` (built from `required`, following `allOf` and references), and an API client's JSON body is `PartialBy<Model, 'field' | …>`. A recursive model that terminates declares its link nullable.
- Schema shapes: aliases keep target identity (`type Alias = Target`); `allOf` becomes an intersection alias; an object whose properties clash with a primitive `additionalProperties` index (TS2411) becomes an intersection alias, otherwise an interface; string-only enums stay TypeScript enums, composed string enums become a type alias plus a same-name `const` object (use `typeof Model.ON` for a member type), numeric and mixed enums become literal unions (`ModelEnumText` kept when `x-enum-text` is given); object-constrained shapes add an optional `never` `Symbol.iterator` key, which needs `ES2015.Iterable` or later in `lib`. Generated types do no runtime JSON validation.
- References: component aliases resolve locally (cycles fail); path-level parameters are inherited and overridden by name and location; unbundled external `$ref`s fail with their URI — bundle or inline them first. Unresolved response references fall back to `Promise<Response>`.

## Wow CQRS discovery

- Aggregates come from root `tags` and operation tags of the form `{contextAlias}.{aggregateName}` (e.g. `example.cart`). One is emitted only when **both** a state snapshot (`.snapshot_state.single`) and a fields definition (`.snapshot.count`) resolve; an aggregate with Wow routes that lacks either is skipped with a warning naming the missing operation, and its operations do not fall back to a plain API client. Malformed Wow metadata (a non-`$ref` state response, a count operation without query fields, a malformed event stream schema) exits 4, naming the operation.
- Commands: operationIds `{context}.{aggregate}.{command}` with a request body and a success response (`200`, else the lowest other 2xx) whose local alias chain reaches `#/components/responses/wow.CommandOk`; `wow.command.send` is skipped. A command whose JSON body is not a `$ref` is skipped with a warning. `pay-order` → `PAY_ORDER` / `payOrder`.
- Events: operationIds ending `.event.list_query`; without one, `DomainEventType` is `never`.
- Tags `wow`, `Actuator`, or naming an aggregate with Wow routes get no API client; an operation tagged with both an API tag and such a tag is left to the command and query clients.

## Generated clients

```typescript
export class CartCommandClient<R = CommandResult> implements ApiMetadataCapable {
  readonly apiMetadata: ApiMetadata;
  constructor(apiMetadata?: ApiMetadata) {
    this.apiMetadata = { ...DEFAULT_COMMAND_CLIENT_OPTIONS, ...apiMetadata }; // merged: a lone fetcher keeps the base path
  }
  @post(CartCommandEndpointPaths.ADD_CART_ITEM)
  addCartItem(@request() commandRequest: CommandRequest<AddCartItemCommand>, @attribute() attributes?: Record<string, unknown>): Promise<R> {
    throw autoGeneratedError(commandRequest, attributes);
  }
}
export class CartStreamCommandClient extends CartCommandClient<CommandResultEventStream> {}

export const cartQueryClientFactory = new QueryClientFactory<CartState, `${CartAggregatedFields}`, CartDomainEventType>({
  contextAlias: EXAMPLE_BOUNDED_CONTEXT_ALIAS,
  aggregateName: 'cart',
  resourceAttribution: ResourceAttributionPathSpec.OWNER,
});
```

- Command clients: `DEFAULT_COMMAND_CLIENT_OPTIONS` is `{ basePath: EXAMPLE_BOUNDED_CONTEXT_ALIAS }`, so `new CartCommandClient({ fetcher })` sends under the `example` prefix; pass `basePath: ''` to reach the service without a gateway. Command types wrap the body in `CommandBody<T>`, declared as `<Body>Command` unless the body is already named `…Command`; path variables are positional parameters in path order; `tenantId`/`ownerId` are omitted and `wow.id` kept; an empty body is `Record<string, never>`. The HTTP method of each command comes from the document. The stream client inherits the constructor.
- Query factory: `aggregateName` is the route segment from the snapshot routes, which differs from the aggregate name under `@AggregateRoute(resourceName = "sales-order")`. The fields type is the union of the field enum's values (`CartAggregatedFields.AGGREGATE_ID` and `'aggregateId'` both compile; a misspelt field does not); annotate queries as `` ListQuery<`${CartAggregatedFields}`> ``. Pass `contextAlias: ''` to the `create*` methods for direct access. Resource attribution is inferred from command paths: `OWNER` (`/owner/{ownerId}`), `TENANT` (`/tenant/{tenantId}`) or `NONE`.
- API clients (non-CQRS tags): with `x-wow-context-alias` the constructor merges over `{ basePath: <context alias> }` like command clients. Method names: `methodNames[operationId]`, else `x-fetcher-method`, else the last dot-separated operationId segment camel-cased (`delete_user_by_id` → `deleteUserById`, `getUser_1` → `getUser1`, `users.list` → `list`); a name depends on its operation alone, and a clash exits 4. Parameters: every path, query and header parameter is a typed positional parameter (`item-id` → `itemId`), required ones first in document order (path, query, header, then the `@body()`), optional ones after, then `httpRequest?: ParameterRequest` and `attributes?`. Bodies: JSON → `PartialBy<Model, …>`, `multipart/form-data` → `FormData`, `application/x-www-form-urlencoded` → `URLSearchParams`, `text/*` → `string`, else `BodyInit`; cookie parameters are left out with a warning. The return type comes from the first success response; `text/*` returns `Promise<string>`. Operations without an operationId or tag are skipped with a warning; tags naming the same client get a numbered class.
- At run time calls reject on any failure the server reports; read them with `toWowError` (`api.md`). `…StreamCommandClient` methods take `COMMAND_STREAM_ENDPOINT`: a server error that ends the stream errors it with a `WowError`, so the `for await` throws.
