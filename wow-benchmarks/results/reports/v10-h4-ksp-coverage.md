# V10 Spike H4: KSP Binds The Annotation Model

Spike H4 of the V10 design (`documentation/designs/v10/decisions.md` on the `v10` branch) asks whether compile-time binding (D37: KSP, no reflection, no fallback) covers the semantics real Wow domains use. It does. The compiler and kernel had to grow to cover them, as the fallback in H4 says ("fill the compiler, never go back to reflection"). A minority of handler signatures use 9.x framework types; those have a V10 form and are rewritten by the migration Skill.

## What Is Measured

`wow-compiler`, `ModelSymbolProcessor`. The processor has three modes, set by the KSP option `wow.model`:

| Mode | What it does |
|---|---|
| `generate` | emits `fun <root>Model(context, ...dependencies): AggregateModel<S>` per aggregate |
| `report` | writes `META-INF/wow/model-report.json` and generates nothing |
| `off` | does nothing |

The five 9.x domains that H5 will also use were run unchanged, through a Gradle init script that sets the mode. `generate` adds `wow-kernel` and `wow-reactor` to the module and compiles what the processor generated, in the module itself.

| Module | Language | Aggregates |
|---|---|---|
| `example-domain` | Kotlin | Cart, Order (and one aggregate with no commands) |
| `example-transfer-domain` | Java | Account |
| `wow-compensation-domain` | Kotlin | ExecutionFailed |
| `wow-view-store-domain` | Kotlin | View, ViewPreferences, DatasetWording |
| `wow-dataset-registry-domain` | Kotlin | Dataset |

## First Run: The Gaps

The first processor bound only `onCommand(body)` with no other parameters, public functions, and the `@CreateAggregate` boolean. On the five domains it found these gaps:

1. **Domain services injected into command functions.** This was the dominant gap. Examples: `NextRetryAtCalculator`, `DatasetTableInspector`, `SharedBoardReferences`, `CreateOrderSpec`.
2. **Functions returning `Mono`.**
3. **`@AllowCreate`.** It cannot be expressed as a boolean "creates".
4. **Java package-private handlers in `example-transfer-domain`.** The processor did not find them, because it looked only at public functions.

## What Was Added

| Gap | Fix |
|---|---|
| Injected services | Parameters after the command become parameters of the generated factory. They are bound once, when the host builds the model (D49). A `DecideScope` parameter receives the attempt's scope. |
| Reactor returns | `wow-reactor` provides `awaitResult(Publisher)`: a `Mono` yields its value and a `Flux` yields a list of events. Generated code calls such functions through `decideSuspending` / `afterSuspending`. |
| `@AllowCreate` | The kernel's creation semantics become `Creation { NONE, REQUIRED, ALLOWED }`. |
| Package-private | Every non-private function is bound. The generated model lives in the root's package. |
| Command metadata | `Command` carries what admission learned (`ownerId`, `spaceId`, `headers`), excluded from the fingerprint. A handler may take `Command<X>` instead of `X`; `DecideScope.command` gives the same to the DSL. |
| Extra results | `DecideScope.putResult` answers the caller with `CommandOutcome.Committed.results`. These results are not persisted. |
| 9.x framework types | The processor reports them as errors, with the V10 form to use, instead of binding them wrongly. |

## Results

There are 38 command functions in 9 aggregates.

| Aggregate | Commands bound | Needs a rewrite | Why |
|---|---:|---:|---|
| Cart | 3 | 0 | |
| Order | 3 | 2 | `CommandMessage<CreateOrder>` plus `CommandResultAccessor`; `ServerCommandExchange<ShipOrder>` |
| Account (Java) | 8 | 0 | |
| ExecutionFailed | 8 | 0 | |
| Dataset | 5 | 0 | |
| View | 0 | 6 | `CommandMessage<X>`: reads the calling application from the header and the aggregate id |
| ViewPreferences | 0 | 1 | same |
| DatasetWording | 0 | 2 | same |
| **Total** | **27** | **11** | |

- Every event sourcing function (32) is bound.
- With the rewrites excluded, `generate` compiles in `example-transfer-domain` (Java sources), `wow-compensation-domain` and `wow-dataset-registry-domain`.
- `example-domain` and `wow-view-store-domain` fail on purpose until rewritten: the processor stops at the reported signatures.

All 11 rewrites are mechanical, which is why they are left to the migration Skill (D3, H5):

| 9.x | V10 |
|---|---|
| `CommandMessage<X>` | `Command<X>` |
| `.header[k]` | `.headers[k]` |
| `.aggregateId.id` | `.ref.id` |
| `ServerCommandExchange<X>`, body unused | `X` |
| `CommandResultAccessor.setCommandResult(k, v)` | `DecideScope.putResult(k, v)` |

## Verification

- `ModelSymbolProcessorTest` compiles annotated aggregates and runs the generated model through the kernel. It covers:
  - sync, `suspend`, `Mono` and `Flux` handlers;
  - dependencies, the decide scope and `Command<X>`;
  - creation modes, after hooks, the `@Name` event name and rejection;
  - the reported problems.
- `KernelTest` and `KernelHooksTest` cover `Creation.ALLOWED`, the admitted command and `putResult`.

## Not Covered Yet

- **`@Blocking` dispatch.** Generated code calls blocking handlers inline.
- **Manifest v0.** It is not emitted yet (roadmap 2.3).
- **Equivalence with the 9.x runtime's parsing.** The comparison here is by handler count and by compiling the generated code. Behavioural equivalence is checked when the domains' tests run on V10 (roadmap 2.6).
