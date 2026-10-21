---
title: OpenAPI and springdoc
description: The OpenAPI 3.1 document of a service's routes, served and generated at build time, and merged into springdoc's documents and Swagger UI with the application's own controllers.
outline: deep
---

# OpenAPI and springdoc

A service's routes come from its Manifest, and so does their OpenAPI document: every command, state read, query and dataset route, with the request and response schemas of the Manifest's types. This page shows where to get the document and how it joins springdoc's.

## Get the document

| Where | When |
| --- | --- |
| `GET /wow/openapi` | At run time, from the service's merged Manifest (`wow.http.openapi.enabled`, on by default) |
| `build/wow/openapi.json` | At build time, from the Gradle plugin's `generateWowOpenApi`, for tools that need it before the service runs |

The document is OpenAPI 3.1, with JSON Schema 2020-12 schemas. It describes the public port only: the internal port's calls, cluster channel and signals are never in it.

## With springdoc

When the application has springdoc on its class path, the Wow document joins springdoc's, so `/v3/api-docs` and Swagger UI list the application's own controllers and the Wow routes together:

- The Wow routes are merged into springdoc's default document. A route both define, by path and method, or a component name both use for different definitions, fails the startup, which renders the document once to check.
- The group `wow.http.openapi.springdoc.group` (`wow`) serves the Wow routes alone, at `/v3/api-docs/wow`.
- Swagger UI lists the default document first, then the groups.
- `/v3/api-docs` and Swagger UI are springdoc's, guarded by the application's security configuration. `GET /wow/openapi` is unchanged.

`wow.http.openapi.springdoc.enabled: false` turns the bridge off.

## Security schemes

The document declares how a caller presents its identity, so Swagger UI's "Try it out" sends it. The host fills that from its caller resolver: CoSec's resolver takes a bearer token. An application with a resolver of its own declares its schemes with a `WowOpenApiSecurity` bean, which replaces the host's.

## Clients from the document

TypeScript clients are generated from the Manifest rather than from the OpenAPI document, so they keep what OpenAPI cannot say, such as wait plans and query fields. The OpenAPI document serves every other language and tool.
