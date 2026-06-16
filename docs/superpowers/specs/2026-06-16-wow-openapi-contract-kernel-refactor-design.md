# wow-openapi Contract Kernel Refactor Design

## Background

`wow-openapi` currently acts as more than an OpenAPI document generator. Its `RouteSpec`
types are used both to render Swagger/OpenAPI operations and to drive WebFlux runtime
route registration. Metadata types such as `CommandRouteMetadata` also mix REST contract
description with runtime command decoding.

This makes the module name and architecture misleading: the real responsibility is HTTP
contract modeling for Wow aggregate, command, state, snapshot, event, query, and global
routes. The refactor should clean that boundary while preserving every external RESTful
API contract.

## Goals

- Rebuild `wow-openapi` around a clean HTTP contract kernel.
- Keep RESTful API behavior compatible.
- Allow breaking Kotlin API, package, SPI, and public type changes.
- Remove long-lived compatibility facades for the old internal architecture.
- Make route collection, ordering, conflict detection, and rendering explicit.
- Decouple OpenAPI rendering from WebFlux runtime route binding.
- Keep this refactor focused on `wow-openapi`, `wow-webflux`, and Spring Boot starter
  wiring. Do not start a `wow-schema` refactor in this phase.

## Compatibility Boundary

Must remain compatible:

- REST path.
- HTTP method.
- Header, query, and path parameters.
- Request body shape.
- Response status, headers, content type, and schema references.
- SSE media type and externally observable streaming behavior.
- OpenAPI `operationId`.
- Existing global endpoints and aggregate endpoints.

May change:

- Kotlin public API.
- Package structure.
- SPI and factory interfaces.
- `RouteSpec`, `OpenAPIComponentContext`, provider classes, and related internal types.
- How WebFlux maps generated contracts to handler functions.

## Chosen Approach

Use the Contract Kernel approach.

Keep the work inside `wow-openapi` first. Do not split a new Gradle module in this phase.
The module should become an explicit REST contract and OpenAPI rendering module:

```text
Wow Metadata
  -> RouteContributor
  -> HttpRouteContract
  -> RouteCatalog
  -> OpenApiRenderer
  -> Springdoc OpenAPI customizer

RouteCatalog
  -> WebFluxRouteAdapter
  -> RouterFunction
```

This keeps external REST behavior stable while allowing internal structure to be cleaned
aggressively.

## Target Components

### HttpRouteContract

Pure REST contract model. It should not depend on Swagger `Operation`, `PathItem`, or
WebFlux types.

It should describe:

- `routeId`, preserving current `operationId`.
- `path`.
- `method`.
- Accepted media types.
- Produced media types when needed.
- Parameters.
- Request body.
- Responses.
- Tags.
- `handlerKey`.
- `resourceScope`, such as global, bounded context, aggregate, or command.

### RouteCatalog

The single collection root for generated route contracts.

Responsibilities:

- Build route contracts from all contributors.
- Keep deterministic ordering.
- Detect duplicate `path + method`.
- Detect missing handler keys.
- Detect path template and path variable mismatch.
- Provide route iteration for OpenAPI rendering and WebFlux route registration.
- Be idempotent and safe for normal Spring bean creation.

### RouteContributor

Replacement for the current ServiceLoader factory pattern.

Each contributor should declare:

- `id`.
- `category`: `GLOBAL`, `COMMAND`, `STATE`, `SNAPSHOT`, `EVENT`.
- `order` for deterministic output only.
- Supported metadata scope.
- Contract creation logic.

Contributor ordering must be explicit. Semantics must not depend on `META-INF/services`
file order.

### Component Registry

The current `OpenAPIComponentContext` combines schema generation and OpenAPI component
collection. Replace it inside `wow-openapi` with a contract-oriented component registry
that can register parameters, headers, request bodies, responses, and schemas through
stable component keys.

This phase should reuse existing `wow-schema` behavior and naming strategy. It should not
rebuild `wow-schema`.

### OpenApiRenderer

The only layer that knows Swagger/OpenAPI model classes.

Responsibilities:

- Render `RouteCatalog` into `OpenAPI`.
- Render route contracts into `Operation` and `PathItem`.
- Add tags.
- Add components.
- Preserve current OpenAPI 3.1 behavior.
- Preserve current `Info` enrichment with Wow context extensions.

### WebFluxRouteAdapter

`wow-webflux` should consume `HttpRouteContract`, not OpenAPI structures and not concrete
`RouteSpec` subclasses.

Runtime handler matching should use `handlerKey`, not `spec::class.java`.

## Migration Plan

### Step 1: Establish REST Contract Snapshots

Before replacing the implementation, add normalized snapshots for the current generated
REST contracts and OpenAPI output using `example-domain`.

Snapshots must cover:

- Paths.
- Methods.
- Operation ids.
- Parameters.
- Request bodies.
- Responses.
- Headers.
- Media types.
- Important schema refs.
- Global endpoints.
- Command, state, snapshot, event, and query route families.

These snapshots protect REST compatibility while internal APIs are broken.

### Step 2: Introduce Contract Kernel Beside Old RouteSpec

Add `HttpRouteContract`, `RouteCatalog`, `RouteContributor`, component registry, and
OpenAPI renderer.

Initially, old `RouteSpec` generation may be adapted into the new model. This keeps
behavior stable while building the new core.

### Step 3: Migrate Route Families

Move existing route families one by one:

- Global routes.
- Command routes.
- State routes.
- Snapshot routes.
- Event routes.
- Query routes.

Each family should move from `RouteSpec` subclasses to explicit contributors that create
`HttpRouteContract`.

After each family migration, run focused tests and snapshot comparison.

### Step 4: Update WebFlux Binding

Change WebFlux route registration from concrete `RouteSpec` class matching to
`handlerKey` matching.

Add verification that every generated contract has a matching handler factory.

### Step 5: Delete Old Architecture

Once all routes use the new contract kernel, remove obsolete structures:

- Old `RouteSpec` hierarchy as the main path.
- ServiceLoader route factory providers as the main path.
- Order-sensitive service files.
- `CurrentOpenAPIComponentContext`.
- WebFlux class-based route handler matching.
- Any compatibility adapters introduced only for migration.

## Error Handling And Conflict Rules

Catalog building should fail early for invalid contracts:

- Duplicate `path + method`.
- Missing `handlerKey`.
- Missing or unbound path variables.
- Path variables declared but absent from the path template.
- Component key conflict with different content.
- Schema key conflict with different content where detectable.
- Invalid media type values.

Warnings are acceptable only for non-contractual documentation quality issues. Anything
that can change runtime REST behavior should fail the build or test.

## Testing Strategy

### Contract Snapshot Tests

Use `example-domain` as the main compatibility sample.

The snapshot should be normalized to reduce noise from map ordering and non-contractual
field order. It should still catch changes to:

- `path`.
- `method`.
- `operationId`.
- parameter location, name, required flag, default, and schema ref.
- request body content type and schema ref.
- response status, headers, content type, and schema ref.
- SSE `text/event-stream`.

### Contract Kernel Unit Tests

Cover:

- Duplicate route detection.
- Path template and variable validation.
- Stable contributor ordering.
- Handler key validation.
- Component conflict detection.
- Catalog build idempotency.

### WebFlux Integration Tests

Cover:

- Every contract has a handler factory.
- RouterFunction can be built from the catalog.
- Request predicate method/path/accept matches contract.
- Command path and header variables still decode into command objects.
- Typical endpoint response status, headers, and media type remain compatible.

## Acceptance Criteria

- `./gradlew :wow-openapi:test` passes.
- `./gradlew :wow-webflux:test` passes.
- Spring Boot starter focused tests pass if affected.
- Contract snapshot comparison passes with no unintended REST diff.
- OpenAPI snapshot comparison passes with no unintended operation diff.
- `CurrentOpenAPIComponentContext` is no longer part of the main route generation path.
- ServiceLoader file order no longer expresses route semantics.
- WebFlux no longer uses concrete `RouteSpec` class matching as the main handler lookup.
- Old compatibility adapters are removed before completion.

## Non-goals

- Do not create a new Gradle module in this phase.
- Do not redesign REST URLs.
- Do not rename external headers.
- Do not change command wait, aggregate tracing, snapshot, event compensation, or query
  business behavior.
- Do not change release, publish, CI, or feature variant configuration.
- Do not refactor `wow-schema` in this phase.
- Do not treat performance optimization as a primary goal.

## Risks

The main risk is silent REST contract drift. Contract and OpenAPI snapshots are required
before changing the main path.

The second risk is WebFlux handler binding failure. The new `handlerKey` model needs
explicit coverage that every generated contract has a handler.

The third risk is schema reference drift. This phase should preserve current schema naming
behavior and only reorganize how `wow-openapi` registers and consumes schema refs.
