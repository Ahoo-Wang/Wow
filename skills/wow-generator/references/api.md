# Wow Generator API Reference

## Contents

- [Installation](#installation)
- [CLI Usage](#cli-usage)
  - [CLI Options](#cli-options)
- [Programmatic API (CodeGenerator)](#programmatic-api-codegenerator)
  - [Key Exports](#key-exports)
- [Code Generation Pipeline](#code-generation-pipeline)
- [Generated Output Structure](#generated-output-structure)
- [Configuration (fetcher-generator.config.json)](#configuration-fetcher-generatorconfigjson)
  - [Property optionality](#property-optionality)
- [Wow CQRS Pattern Support](#wow-cqrs-pattern-support)
  - [Aggregate Identification](#aggregate-identification)
  - [Operation Patterns](#operation-patterns)
  - [API Client Tag Exclusion](#api-client-tag-exclusion)
  - [Command Clients](#command-clients)
  - [Query Clients](#query-clients)
  - [API Clients](#api-clients)
- [Integration with Fetcher](#integration-with-fetcher)
- [Package Reference](#package-reference)

TypeScript code generator producing type-safe API clients from OpenAPI 3.0+ specs via `@ahoo-wang/wow-generator` (formerly `@ahoo-wang/fetcher-generator`), with specialized Wow CQRS/DDD framework support. Its source lives in the Wow repository under `typescript/wow-generator/src`.

## Installation

```bash
pnpm add -D @ahoo-wang/wow-generator
pnpm add @ahoo-wang/wow-client @ahoo-wang/fetcher @ahoo-wang/fetcher-decorator @ahoo-wang/fetcher-eventstream
```

Generated code imports `@ahoo-wang/wow-client`, `@ahoo-wang/fetcher`, and `@ahoo-wang/fetcher-decorator` at runtime, so the application depends on them directly. `@ahoo-wang/fetcher`, `@ahoo-wang/fetcher-decorator`, `@ahoo-wang/fetcher-eventstream`, and `@ahoo-wang/fetcher-openapi` keep their names, stay in the fetcher project, and are peer dependencies of the generator.

When migrating from `@ahoo-wang/fetcher-generator`, replace the dev dependency, change scripts from `fetcher-generator generate` to `wow-generator generate`, and regenerate so generated imports point at `@ahoo-wang/wow-client` instead of `@ahoo-wang/fetcher-wow`. The `fetcher-generator` command stays as an alias until v10; the configuration file keeps its name `fetcher-generator.config.json`.

## CLI Usage

```bash
# Basic usage
npx wow-generator generate -i ./openapi.yaml -o ./src/generated

# With config file
npx wow-generator generate -i ./openapi.yaml -o ./src/generated -c ./fetcher-generator.config.json

# From URL
npx wow-generator generate -i https://api.example.com/openapi.json -o ./src/generated

# With TypeScript config
npx wow-generator generate -i ./openapi.yaml -o ./src/generated -t ./tsconfig.json
```

### CLI Options

| Flag                               | Description                                     | Default                           |
| ---------------------------------- | ----------------------------------------------- | --------------------------------- |
| `-i, --input <file>`               | OpenAPI spec file (JSON/YAML) or HTTP/HTTPS URL | **required**                      |
| `-o, --output <path>`              | Output directory path                           | `src/generated`                   |
| `-c, --config <file>`              | Configuration file path                         | `./fetcher-generator.config.json` |
| `-t, --ts-config-file-path <file>` | TypeScript config file path                     | —                                 |
| `-v, --version`                    | Display version                                 | —                                 |

## Programmatic API (CodeGenerator)

The published package supports both ESM imports and CommonJS
`const { CodeGenerator } = require('@ahoo-wang/wow-generator')`.

`logger` is a required option (`Logger` interface: `info`/`success`/`error`/`progress`/`progressWithCount`). The package does not export a logger implementation — provide your own:

```typescript
import { CodeGenerator } from '@ahoo-wang/wow-generator';

const generator = new CodeGenerator({
  inputPath: './openapi.yaml',
  outputDir: './src/generated',
  tsConfigFilePath: './tsconfig.json',
  logger: {
    info: console.info,
    success: console.info,
    error: console.error,
    progress: console.info,
    progressWithCount: (current, total, message) =>
      console.info(`${message} (${current}/${total})`),
  },
});
await generator.generate();
```

Re-running generation with the output included in the supplied tsconfig
rebuilds the generated declarations instead of appending duplicates.
Each successful run records generated files and their content hashes in a
`.fetcher-generator.json` manifest at the root of the output directory, including barrel indexes. Later runs remove
files no longer generated only when they remain unchanged, then rebuild indexes
without exports for removed output. This also works across generator instances
when the output is not included in tsconfig. Handwritten files, modified stale
files, and legacy output without an ownership record are preserved; keep the
manifest with the generated output to enable cleanup. Only current generated
files are formatted and saved. Rendering failures leave existing output intact,
and retrying the same instance discards drafts from the failed run. Filesystem
save failures can leave partial output; generation is not a directory transaction.

### Key Exports

`CodeGenerator`, `DEFAULT_CONFIG_PATH` (`./fetcher-generator.config.json`) — that is the complete public surface; option and config interfaces (`GeneratorOptions`, `GeneratorConfiguration`) are not re-exported from the package entry.

## Code Generation Pipeline

Component aliases are resolved locally; cyclic component aliases fail generation. When a referenced model shares a name with a declaration or another import in the generated file, the import receives a distinct local alias; repeated references reuse it. The public generated model keeps its original component name. Potential `EnumText` names are reserved before generation, so schema order cannot create an import collision.
Schema aliases retain their target type identity (`type Alias = Target`), including
enum aliases across model files. Response aliases are resolved before selecting
the JSON, text, or event-stream decoder.
Path-level parameters are inherited by operations; an operation overrides a
parameter with the same name and location. API and command clients both resolve
custom component parameter references and aliases before binding path arguments.
Command clients retain `wow.id` and continue to omit `tenantId`/`ownerId` arguments.
JSON string responses are decoded as JSON.

Command identification follows local response aliases only when their chain reaches
`#/components/responses/wow.CommandOk`; matching an unrelated response shape is not
sufficient. Inline and locally referenced command request bodies are supported.
Component lookup stays within the referenced local component category at every
alias hop. External documents are not fetched or substituted with same-named local
components. Unbundled external schema references fail with their URI and bundling
guidance instead of generating a local model name. Unresolved response references keep the raw `Promise<Response>` fallback;
unresolved parameter references do not produce named path arguments. Bundle or
inline external references before generation for complete typed signatures.

```
parseOpenAPI(inputPath) → AggregateResolver(openAPI).resolve()
  → ModelGenerator.generate() → ClientGenerator.generate()
    → Index Generator → Optimize (formatText, organizeImports, fixMissingImports)
```

1. **parseOpenAPI** - Parse JSON/YAML spec (local file or URL)
2. **AggregateResolver** - Identifies aggregates from tags (`{context}.{aggregate}` pattern), extracts commands, state, events, fields
3. **ModelGenerator** - Generates TypeScript types/enums from schemas (skips `wow.*` schemas except `wow.api.query.*PagedList` and `wow.api.query.Operator*Map`; the exact base `wow.api.query.PagedList` is skipped too, and aggregate-specific `*PagedList` schemas map to `PagedList` from `@ahoo-wang/wow-client`)
4. **ClientGenerator** - Generates QueryClient, CommandClient, StreamCommandClient, ApiClient per aggregate
5. **Index Generator** - Creates `index.ts` barrel exports at every directory level
6. **Post-processing** - `formatText()`, `organizeImports()`, `fixMissingImports()` on all files

Generated object properties follow each schema's `required` list, including nested
objects and `allOf` siblings. Read-only properties retain `readonly` in nullable
and inline object aliases. Required keys absent from `properties` follow
`additionalProperties`, remain open to extra keys when that option is omitted,
and preserve custom `x-map-key-schema` constraints. Forbidden required keys have
type `never`; required names use own-property checks and escaped property names.
For undeclared required keys with default or `true` additional properties, the
fallback accepts primitive/null values, dictionaries, and readonly arrays while
rejecting function values, so inherited prototype methods do not satisfy them.
TypeScript cannot prove arbitrary properties are own properties or recursively
validate JSON values through these structural types.
OpenAPI 3.0 `nullable: true` permits `null` only when enum, const, and composition
constraints also allow it. A standalone `required` list only constrains objects.
Without `type`, nonempty `properties` still constrain object instances, including
beside compositions; these object branches remain open when
`additionalProperties` is omitted. The unconstrained non-object branch includes
mutable and readonly arrays.
Object constraints in `allOf`, including nested compositions and references,
exclude primitive and array branches. Object-constrained compositions and nonempty
enum/const object literals, including nested literals, also add an
optional `globalThis.Symbol.iterator` property of type `never`, so readonly arrays
cannot satisfy a weak object shape. This boundary requires `ES2015.Iterable` or a
higher standard library, such as the repository's ES2020 library. Custom iterable
objects must first be converted to plain JSON objects; this is not a general
TypeScript representation of every JavaScript object that is not an array.
Mixed `oneOf` and `anyOf` branches retain their allowed non-object values.
Empty root properties do not erase composition constraints. When multiple
composition keywords occur together, each `allOf` intersection and `oneOf` or
`anyOf` union is retained, then their results are intersected. A `type` array
applies the same schema constraints to each listed type before forming its union,
so object/array alternatives do not introduce an unconstrained `any`. Repeated
composition nodes and edges are collected once, then object constraints propagate
until stable. Recursive references do not produce path-dependent partial cache
entries, and repeated DAG branches reuse the same collected nodes. A component named
`Exclude` remains usable alongside composed object schemas. Generated `Record`
references use `globalThis.Record` so a component named `Record` cannot shadow
the utility type.

All `allOf` schemas generate TypeScript intersection aliases so required,
optional, and conflicting property types retain every member constraint. Object
schemas with an optional property beside schema-valued `additionalProperties`
also use an intersection alias: the declared properties retain their modifiers
while the string index keeps the additional-property type without adding
`undefined`. A TypeScript index signature constrains the declared keys too, so
an interface may only carry a named property assignable to it (TS2411). Every
generated property is required, so the form is chosen from the property's kind
alone: against an index type that resolves to a primitive, a property that
resolves to a different primitive, to an object, to an array, or to a kind the
schema does not decide takes the alias instead. Undecided counts as a clash
because a nullable property, a type array and a typeless enum each generate a
union no primitive index accepts. An enum is decided by the sibling type it
narrows, so `'a' | 'b'` beside a `string` index stays an interface, and an
`allOf` whose branches all decide the same kind is decided the same way. The
index type must resolve to a primitive for any of this, which is what lets a
dictionary of its own type generate at all: only an interface may reference
itself through an index signature, an alias reaching itself through `Record`
being circular (TS2456). A property may reference the model freely, an object
member defers. Other plain object schemas continue generating interfaces.
Neither form expresses the JSON Schema case where a declared property's type is
incompatible with `additionalProperties`: the alias declares and reads
correctly but admits no object literal, since TypeScript cannot exempt a named
property from the index signature.
Generated types do not perform runtime JSON validation.

String-only enums with no const or composition constraints, and with `type` omitted or set to `'string'`, remain TypeScript enums (including empty-string members).
Separator-only values whose normalized name is empty retain their original key,
so `Model['']` and `Model['-']` are distinct; the same keys apply to `EnumText` and
composed enum value objects. When a string enum also has `allOf`, `oneOf`, or `anyOf`, it generates a constrained type
alias and a same-name `const` object, preserving runtime access such as
`Model.ON`. The object retains every declared enum value; the type alias rejects
values excluded by the composition. Use `typeof Model.ON` for a member's type in
this form. `ModelEnumText` remains available when `x-enum-text` is supplied.
Numeric and mixed enums use literal unions that preserve the JSON value types and keep `ModelEnumText` when supplied. Mixed enums also export a same-name `const` object for their existing string members. Enum and const literals are intersected with supported type, object, and composition constraints; contradictory literal and type constraints reject every value. Known literals are checked against JSON type categories, so arrays do not satisfy `object` and fractional numbers do not satisfy `integer`. Empty object literals, including nested enum/const objects, use `globalThis.Record<string, never>` rather than the broad TypeScript `{}` type.

## Generated Output Structure

```
output/
├── index.ts                        # Root barrel exports
├── {bounded-context}/
│   ├── index.ts                    # Context barrel exports
│   ├── boundedContext.ts           # Context alias constant (e.g., EXAMPLE_BOUNDED_CONTEXT_ALIAS)
│   ├── types.ts                    # Shared types for this context path
│   ├── {Tag}ApiClient.ts           # API client per non-CQRS tag
│   └── {aggregate}/
│       ├── index.ts
│       ├── commandClient.ts        # CommandClient + StreamCommandClient + CommandEndpointPaths
│       └── queryClient.ts          # QueryClientFactory + DomainEventType + DomainEventTypeMapTitle
├── {other-schema-path}/
│   ├── types.ts                    # Types for schemas in other dot-separated paths
│   └── ...
```

Model files use `types.ts` named by schema path prefix (e.g., schema key `ai.AiMessage.Assistant` maps to `ai/types.ts` with type `AiMessageAssistant`).

## Configuration (fetcher-generator.config.json)

```json
{
  "apiClients": {
    "TagName": {
      "ignorePathParameters": ["tenantId", "ownerId"]
    }
  }
}
```

- `apiClients` - Map of tag name to API client configuration
- `ignorePathParameters` - Path parameters to exclude from generated **API client** methods (default: `['tenantId', 'ownerId']`). Command clients always ignore `tenantId`/`ownerId` regardless of this setting.

`loadConfiguration` (`typescript/wow-generator/src/utils/configuration.ts`) reads it. The default path is optional and resolved against the working directory; everything else is loud. A `--config` path that does not exist, content that will not parse, and an option with the wrong shape each fail the run, and unknown keys warn by name. The log records the absolute path and the resolved settings (`apiClients=…`), so a silent no-op — wrong working directory, stale binary — is visible in one run instead of looking like a generator that ignores its options.

### Property optionality

Every property a schema declares is generated as required — no `?` is ever emitted for a model property. A statically typed service has no absent `int` or `boolean` to hand back, so optionality read off `required` describes the exporter rather than the wire format (exporters routinely drop properties carrying a default value).

Optionality the document genuinely means is carried elsewhere:

| Meaning                        | Where it lives         | Generated as                                                                                                                                                                                               |
| ------------------------------ | ---------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| the value may be null          | the property type      | `T \| null` (every spelling of null in 3.0 `nullable` and 3.1 type arrays)                                                                                                                                 |
| a command field may be omitted | the command type alias | `CommandBody<PartialBy<Command, 'field' \| …>>`, built by `resolveOptionalFields` from the document's `required`, following `allOf` branches and references so an inherited optional field is not demanded |

Requiring model properties therefore does not narrow what a _command_ caller may send: `resolveCommandType` (`typescript/wow-generator/src/client/commandClientGenerator.ts`) still wraps the body in `PartialBy`. An ordinary operation's request body is typed as the model itself, so it is required in full — a deliberate, documented consequence.

`requiresAdditionalPropertiesIntersection` reads only `clashesWithIndexSignature`, since no property carries `undefined` any more. That predicate errs towards the intersection: against a primitive index, an object, an array, a different primitive **and any property whose kind cannot be read off the schema** all clash, because a nullable property, a type array and a typeless enum each generate a union no primitive index accepts (TS2411). An enum is decided by the sibling type it narrows, so `'a' | 'b'` beside a `string` index stays an interface. Undecided cannot be circular (TS2456) either, since the index resolved to a primitive before the property was consulted. Schemas whose `required` names a key with no `properties` entry still gain that key, typed from `additionalProperties`.

A non-nullable self-reference has no finite literal — every level needs the next — so a recursive model that terminates declares its link nullable and generates `T | null`.

## Wow CQRS Pattern Support

### Aggregate Identification

Aggregates are discovered **only from the root `tags:` array** — tags appearing solely on operations are ignored. Tags following `{contextAlias}.{aggregateName}` pattern identify aggregates (e.g., `example.cart`).

An aggregate is emitted only when **both** a state snapshot and a fields definition resolve for it; otherwise the tag falls through to plain API client generation.

### Operation Patterns

- **Commands**: Operation IDs matching `{context}.{aggregate}.{command}` with a request body and an OK response `$ref: #/components/responses/wow.CommandOk` ("OK" means strictly `responses['200']`; the operation `wow.command.send` is skipped)
- **State Snapshots**: Operation IDs ending with `.snapshot_state.single`
- **Events**: Operation IDs ending with `.event.list_query`
- **Fields**: Operation IDs ending with `.snapshot.count`

### API Client Tag Exclusion

Tags named `wow`, `Actuator`, or matching aggregate names are excluded from API client generation.

### Command Clients

```typescript
// Regular command client
export class CartCommandClient<
  R = CommandResult,
> implements ApiMetadataCapable {
  constructor(
    public readonly apiMetadata: ApiMetadata = DEFAULT_COMMAND_CLIENT_OPTIONS,
  ) {}
  @put(CartCommandEndpointPaths.ADD_CART_ITEM)
  addCartItem(
    @request() commandRequest: CommandRequest<AddCartItemCommand>,
    @attribute() attributes?: Record<string, any>,
  ): Promise<R> {
    throw autoGeneratedError(commandRequest, attributes);
  }
}
// Stream variant (extends CommandClient<CommandResultEventStream>)
export class CartStreamCommandClient extends CartCommandClient<CommandResultEventStream> {}
```

Command types use `CommandBody<T>` wrapper. `CommandEndpointPaths` enum maps command names to paths.

### Query Clients

```typescript
export const cartQueryClientFactory = new QueryClientFactory<
  CartState,
  CartAggregatedFields | string,
  CartDomainEventType
>({
  contextAlias: EXAMPLE_BOUNDED_CONTEXT_ALIAS,
  aggregateName: 'cart',
  resourceAttribution: ResourceAttributionPathSpec.OWNER,
});
```

When an aggregate has no `.event.list_query` operation, its generated `DomainEventType` is `never`.

Resource attribution inferred from command paths: `ResourceAttributionPathSpec.OWNER` (`/owner/{ownerId}`), `ResourceAttributionPathSpec.TENANT` (`/tenant/{tenantId}`), or `NONE`.

### API Clients

Generated for non-CQRS endpoints. Parameters `tenantId`/`ownerId` are ignored by default.

## Integration with Fetcher

```typescript
import { Fetcher } from '@ahoo-wang/fetcher';
import { cartQueryClientFactory } from './generated/example/cart/queryClient';
import { CartCommandClient } from './generated/example/cart/commandClient';

const fetcher = new Fetcher({ baseURL: 'https://api.example.com' });
const snapshotClient = cartQueryClientFactory.createSnapshotQueryClient({
  fetcher,
});
const commandClient = new CartCommandClient();
```

## Package Reference

- [Package Source](https://github.com/Ahoo-Wang/Wow/tree/main/typescript/wow-generator/) - Source code and README
- CLI: `wow-generator` (`fetcher-generator` remains an alias of the same binary until v10); programmatic entry: `CodeGenerator` (see Key Exports above)

Aggregate framework event-stream and materialized-snapshot cursor-page wrappers are skipped alongside their paged-list wrappers. Their query behavior is provided by `QueryClientFactory` from `@ahoo-wang/wow-client`; ordinary business cursor-page models remain generated.
