# Runtime and REST Validation

Use this reference after source/platform adaptation and before any deployability, data, cutover, or production-readiness claim. Runtime dependency and startup gates always apply; HTTP checks apply only when the scoped application exposes an HTTP capability.

## Evidence gates

Keep each layer independent. Record the exact command or request, result, and limit of what it proves.

| Gate | Required evidence | Does not prove |
|---|---|---|
| Compile/test/build | Actual compile, focused tests, and build result | Resolved runtime graph, startup, HTTP, integrations |
| Resolved dependencies | Target module's resolved dependency tree and conflict/variant selection | Class presence on the launched runtime path |
| `runtimeClasspath` | Final application runtime configuration and required class ownership | Successful initialization or endpoint behavior |
| Application startup | A target-version process reaches the intended ready state | Correct REST contracts, external integrations, or data safety |
| Real HTTP or actual transport | Requests/messages cross the selected server/router/binding or messaging stack | External systems, migration reconciliation, or production readiness |
| External integration/data | Authorized calls or writes against an exact isolated target | Production cutover or rollback readiness |
| Deployability | Immutable artifact, runtime dependencies, deploy configuration, and startup contract are verified | Production data, traffic, observability, approval, or rollback readiness |
| Production readiness | Environment, data, reconciliation, traffic, monitoring, rollback, and approval evidence | Nothing beyond the exact proved scope |

A passing gate never substitutes for a later gate. A smoke test through temporary isolation proves only that isolated path.

## Prove the target runtime platform

1. Compare the pinned Wow tag, BOM, official project template, selected starter/storage modules, published Gradle/Maven metadata, and their actual runtime dependencies.
2. Inspect whether the target Spring Boot release split an auto-configuration package into a separate module. For every critical auto-configuration class referenced by the selected Wow starter or storage module, identify the artifact that owns the class in that Boot version.
3. Verify the owning artifact is present in the launched application's final `runtimeClasspath`; compile classpath or successful compilation is insufficient.
4. When a class is missing, record the referencing Wow artifact/class, missing class, expected owning artifact, dependency path, and why it was absent. Use the real application module and configuration, for example:

```bash
./gradlew :app:dependencies --configuration runtimeClasspath
./gradlew :app:dependencyInsight --configuration runtimeClasspath --dependency <artifact-or-module>
jar tf <resolved-artifact.jar> | rg '<ClassName>|<package/path>'
```

For Maven applications, use the corresponding runtime-scoped `dependency:tree` and artifact inspection. Resolve actual module/task names from the target checkout.

Do not prescribe a Boot module globally. Add or report a module only when the application selects the relevant Wow capability, the pinned target platform owns a required class there, and the final runtime path lacks it. Treat a temporary dependency experiment as diagnostic evidence until the permanent dependency owner is established.

## Start safely in isolation

First determine whether the supported unmodified local/test startup can run without unauthorized external calls or data writes. Run it and preserve its result only when that boundary is safe and authorized; otherwise record the original startup as not attempted with `MISSING EVIDENCE`. Before attributing a failure to migration code, check writable application/GC log directories, temporary directories, HTTP/JMX/debug port availability, JVM options, and required local files. Classify these separately as `runtime preflight failure`.

For an isolated retry:

1. Prefer an existing project-owned test/local profile.
2. If none exists, derive temporary command-line/property overrides from the pinned target's actual `@ConfigurationProperties`, tests, and official template. Switch Wow command/event/state buses, event store, and snapshot store to available in-memory implementations only when the target version proves those options.
3. Disable Kafka, PrepareKey, service discovery, external configuration, schedulers/consumers that write, and third-party business calls using existing supported flags or test beans. Do not invent property names.
4. Bind only to loopback and use non-conflicting temporary HTTP/JMX settings. Do not hide a real application failure by suppressing unrelated auto-configuration without proving the boundary.
5. Do not read or print secret values or complete configuration files. Inspect key names/schema or redacted output; report plaintext credentials as a rotation requirement.
6. Do not write application data or call external APIs without an exact target and explicit authorization. If safe isolation cannot be proved, stop that gate and record `MISSING EVIDENCE`.
7. Do not create temporary profiles, configuration, or dependency edits during a read-only audit. When code writes are authorized, keep isolation experiments outside the committed diff, record them verbatim with secret values redacted, and verify they were not committed.

For every start, report whether it was original or isolated, the first root cause, process/port used, readiness reached, and whether the failure is an application migration defect, runtime dependency defect, preflight failure, unavailable external integration, or authorization block.

## Exercise the real application transport

First prove which transport capabilities the scoped application selects. If it has no HTTP surface, mark HTTP-specific rows `NOT APPLICABLE` with dependency/configuration evidence and validate the actual messaging or other transport instead; do not add Actuator, OpenAPI, WebFlux, or Controller dependencies to satisfy this checklist.

For an application with an HTTP surface, issue real requests against the started process and record method, path, status, content type, and a non-sensitive response summary. Cover each applicable item:

- `/actuator/health`, `/actuator/health/liveness`, and `/actuator/health/readiness` when Actuator/probes are selected and exposed; explain which external dependency makes health `DOWN` instead of rewriting it as a false pass;
- `/v3/api-docs` and the enabled Swagger UI route when OpenAPI/Swagger is selected;
- OpenAPI application name, published application version, path count, and critical paths, compared with an explicit baseline or expectation rather than a fixed universal count;
- one Wow-generated query/state endpoint when the corresponding Wow WebFlux/OpenAPI capability is selected; use a command endpoint only with an authorized isolated write target;
- one application-owned Controller endpoint when one exists and is confirmed read-only or its side effects are explicitly authorized;
- one read-only query when the application exposes one;
- missing query, path, and body inputs; malformed body; and validation failures for each applicable REST binding contract;
- graceful service stop and Wow shutdown completion.

For `@HttpExchange` and Controller contracts, inspect explicit `@RequestParam`, `@PathVariable`, and `@RequestBody` bindings together with generated OpenAPI. A required value missing or invalid must produce the contract's reasonable 4xx response. Treat a 500 caused by `null` reaching a Kotlin non-null parameter as a REST compatibility regression even when OpenAPI generation succeeds.

## Report the gate

Report:

- original startup result and first failure;
- temporary isolation arguments, profiles, and dependencies, clearly marked non-production and uncommitted;
- resolved dependency and `runtimeClasspath` gaps;
- an HTTP or transport evidence table with requests/messages, statuses/outcomes, and key non-sensitive summaries;
- health/readiness limitations caused by disabled or unavailable integrations;
- REST parameter-binding regressions and expected 4xx behavior;
- graceful shutdown result;
- passed, failed, skipped, `NOT APPLICABLE`, and `MISSING EVIDENCE` gates. Use `NOT APPLICABLE` only when scoped capability evidence proves the surface does not exist; use `MISSING EVIDENCE` when it should exist but was not verified.

Finish with four independent statuses: **migration code complete**, **local runtime passed**, **deployable**, and **production ready**. State the evidence for each; never infer one from another. List external services, data migration/reconciliation, cutover, rollback, production configuration/secrets, and production observability separately.
