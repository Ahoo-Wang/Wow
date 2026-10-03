---
title: View Engine
description: What the wow-view-engine package does, the facts its design rests on, and where to start reading.
---

# View Engine

::: info On npm since Wow 9.2.0
`@ahoo-wang/wow-view-engine` is on npm since Wow 9.2.0, released from the same tag and with the same version as Wow. From 9.2.0 on, a patch release never breaks its public surface: the exports of every entry, including the `ViewStore` port a backend implements; the [CSS contract](./view-engine-theming.md#what-is-public); the message keys and issue codes; and the `wow-view-engine` command. A minor release may, and its release notes list every break with the steps to follow; keep the Wow packages on one minor ([version ranges](./compatibility.md#version-ranges)). Views saved in an older form keep opening: the engine migrates stored configs on read.
:::

The view engine is a data view engine for Wow-based business applications. The application declares in code _how a dataset can be observed_: fields, kinds, operators, available dimensions, and metrics. Users decide in the UI _how to observe it this time_: filters, columns, sorting, dimensions and metrics, charts, and panel composition. The engine compiles that choice into Wow queries, runs them through `@ahoo-wang/wow-client`, renders the result, and saves the views worth keeping so they reopen with one click.

It succeeds `@ahoo-wang/fetcher-viewer`, which stays on Fetcher 5.x and is not documented here.

## Where to start

| To | Read |
|---|---|
| Wire one business object from zero: install, the query descriptor, `defineView`, the engine, `ViewHost`, one action, run against the example server | [Getting Started with the View Engine](./view-engine-getting-started.md) |
| Learn the words first: definition, record and analysis views, boards, system / shared / personal views, revision and the store, and how they connect | [View Engine Core Concepts](./view-engine-concepts.md) |
| Write a definition: the descriptor's facts, text keys, narrowing, `rowFields`, system views and boards, checking it with `admit` | [Writing a Definition](./view-engine-definitions.md) |
| Wear a built-in look, use a brand colour, or follow the host's shadcn theme | [Theming the View Engine](./view-engine-theming.md) |
| Keyboard, screen readers and WCAG 2.2 AA conformance | [Accessibility of the View Engine](./view-engine-accessibility.md) |
| Fit the engine into an app: `ViewHost`, `bind`, the router port, navigation, embeds, messages and locale, testing with `/testing` | [Fitting the View Engine into a Host](./view-engine-host.md) |
| Commands on records: availability rules, placement, confirmation and forms, bulk, outcomes | [Declared Actions](./view-engine-actions.md) |
| Run under a strict Content Security Policy | [Content Security Policy for the View Engine](./view-engine-csp.md) |
| Choose a store: in memory, a snapshot in the browser, a Wow server, or your own `ViewStore` checked by the conformance suite | [Where Views Live](./view-engine-storage.md) |
| Embed the view store in a Kotlin service, or run the standalone server: properties, storage, system views, gateway rules | [View Store](../extensions/view-store.md) |
| Draw your own UI instead of the workbench, with the headless hooks of `/react` (`useOpenView`, `useViewRuntime`, `useFilterEditor`, `useRecordTable`) | [React Hooks](../../reference/typescript/wow-view-engine/react.md) |
| Look up a public name's signature | The [wow-view-engine reference](../../reference/typescript/wow-view-engine/)'s topics: [Engine and Resources](../../reference/typescript/wow-view-engine/engine.md), [Definitions and Field Kinds](../../reference/typescript/wow-view-engine/definitions.md), [Host Wiring](../../reference/typescript/wow-view-engine/host.md), [Workbenches and Embeds](../../reference/typescript/wow-view-engine/components.md), [Persistence Port](../../reference/typescript/wow-view-engine/store.md), [Testing Helpers](../../reference/typescript/wow-view-engine/testing.md), [Issue Codes](../../reference/typescript/wow-view-engine/issues.md) |
| Reach the Wow server's view store from the browser with `WowViewStore` | [wow-view-store reference](../../reference/typescript/wow-view-store/) |

## The problem it solves

Most pages in a business system are the same page: a list with filters, sorting, and paging, sometimes with a chart. Each business object gets its own copy, and each request such as "add one more filter" or "break this down by warehouse" becomes a code change and a release. The data did not change; only the way of observing it did.

| For | What they get |
|---|---|
| Business users | Adjust scope, organization, and presentation within the declared capabilities, save the views that matter, and reopen them with one click |
| Developers | One definition and one query client per business object instead of one page per list, analysis, and overview; filtering, paging, saving, and conflict handling are implemented once |
| The product | Presentation changes within the supported range become configuration; adding a business object adds a definition, never a branch inside the engine |

It is not a database, a permission system, a general low-code page builder, or a BI modeling tool. Data, aggregation capabilities, and authorization come from the Wow services.

## Three facts

The design fixes three facts and derives the rest from them:

| Fact | Consequence |
|---|---|
| Definitions are code | No definition service or definition versions. A definition change is a deploy; saved views are validated when opened |
| Configs are data | Only `ViewInstance` and personal preferences persist. Consistency is an optimistic revision plus an idempotent `requestId` |
| Runtime state is transient | Drafts, results, paging, and selection live in one open `ViewRuntime` and are never persisted |

```mermaid
flowchart LR
    Definition["ViewDefinition<br>code"] --> Engine["ViewEngine"]
    Store["ViewStore<br>saved ViewInstance"] --> Engine
    Engine --> Runtime["ViewRuntime<br>draft, result, selection"]
    Runtime --> Query["wow-client query<br>paged, cursor, aggregate"]
    Query --> Server["Wow snapshot query API"]
    Runtime --> UI["Workbench or your own UI"]
```

## Views

| View | What the user does |
|---|---|
| Record view | Filter status = pending, sort by creation time, keep only the needed columns, save as "Pending today" |
| Analysis view | Take warehouse as the dimension, count orders and sum amounts as metrics, switch to a bar chart |
| Dashboard | Place several views on one page and constrain them with a global time range |
| Embedded view or dashboard | Show a saved view inside a business page, for example a customer's orders, without the workbench |
| System views | Declare "All", "Pending", and "New this week" in the definition so users open a usable view at once |

## Content Security Policy

The engine runs under a strict Content Security Policy: `script-src 'self'` and `style-src 'self'`, with neither `'unsafe-inline'` nor `'unsafe-eval'`. Three things need allowing — the stylesheet loaded as a file, the styles the bundled libraries add carrying the page's nonce, and a `blob:` image for the PNG export. Why each one, how to write the policy, and the tests that hold the engine to it are in [Content Security Policy for the View Engine](./view-engine-csp.md).

## Try it in Storybook

Each view runs in [Storybook](/storybook/) against in-memory fixtures, inside a host application shell. Saving, renaming, and deleting write to a fresh in-memory store on every visit. The Storybook is written in Chinese only.

The [integration walk-through](/storybook/?path=/docs/view-engine-接入导览--docs) in Storybook takes a host through the recommended wiring in five steps — declare a definition, connect the data, mount `ViewHost`, declare the actions, render the page — with the real source files of a working example.

To see what the engine draws first, open the [chart showcase board](/storybook/?path=/docs/view-engine-业务场景-图型全景--docs): one retail dashboard that uses each of the 22 chart types once, every chart titled with the analytical question it answers, on four tabs (trend, mix, spread and relationships, regions and conversion). Pressing a province on the map or the bar chart, or a payment method on the pie, filters the whole board; the docs page's "Show code" holds the board's whole configuration.

| View | Storybook |
|---|---|
| Record view | [Record workbench](/storybook/?path=/docs/view-engine-组件状态-记录工作台--docs) and its [filter editor](/storybook/?path=/docs/view-engine-组件状态-筛选编辑器--docs) |
| Analysis view | [Analysis workbench](/storybook/?path=/docs/view-engine-组件状态-分析工作台--docs) |
| Dashboard | [Dashboard](/storybook/?path=/docs/view-engine-组件状态-仪表盘--docs) |
| Embedded view or dashboard | [EmbeddedView](/storybook/?path=/docs/view-engine-组件状态-embeddedview--docs) and [EmbeddedDashboard](/storybook/?path=/docs/view-engine-组件状态-embeddeddashboard--docs) |
| Themes | [Theme gallery and contrast matrix](/storybook/?path=/docs/view-engine-能力-主题与预设--docs) |

## Where to read more

- [Getting Started with the View Engine](./view-engine-getting-started.md): the example server's sales orders wired in from zero, one file a step.
- [View Engine Core Concepts](./view-engine-concepts.md): definitions, views, boards, system / shared / personal views, revision and the store — what each is and why they are split this way.
- [Writing a Definition](./view-engine-definitions.md): start from the descriptor's facts, make each choice, write the words and the system views, and check it with `admit`.
- [Fitting the View Engine into a Host](./view-engine-host.md): one engine, the ports of `ViewHost`, `bind`, the router, embeds, messages and locale, and testing the wiring with `/testing`.
- [Declared Actions](./view-engine-actions.md): how commands on records are declared, and how the engine places, confirms, runs in bulk and reports them.
- [Content Security Policy for the View Engine](./view-engine-csp.md): the three things a strict policy must allow, and the tests that hold the engine to it.
- [Where Views Live](./view-engine-storage.md): choose a store by who must see the saved views, wire `WowViewStore`, or write your own and run the conformance suite.
- [View Store](../extensions/view-store.md): the Kotlin server — the starter or the standalone server, the Docker image, properties, system views, and the [CoSec gateway rules](../extensions/view-store.md#security-model) it needs.
- [wow-view-engine reference](../../reference/typescript/wow-view-engine/): entries, concepts, persistence port, and extension points; its [topics](../../reference/typescript/wow-view-engine/#topics) give the signatures a host uses, one by one, and [Issue Codes](../../reference/typescript/wow-view-engine/issues.md) lists every code the engine can report.
- [wow-view-store reference](../../reference/typescript/wow-view-store/): `WowViewStore`, the saved views on a Wow server.
- [Theming the View Engine](./view-engine-theming.md): presets, host variables, light, dark and system mode, the shadcn bridge, and the contrast an override owes.
- [Accessibility of the View Engine](./view-engine-accessibility.md): the WCAG 2.2 AA conformance statement, the keyboard and screen-reader walkthroughs, and the known gaps.
- [Design documents](https://github.com/Ahoo-Wang/Wow/tree/main/typescript/wow-view-engine/docs/design): the source of truth for the model.
- [Package README](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/README.md): the API as it stands today.
