---
title: 'Workbenches and Embeds'
description: 'The props of DataWorkbench, DashboardWorkbench, EmbeddedView and EmbeddedDashboard — @ahoo-wang/wow-view-engine/ui'
---

# Workbenches and Embeds

`/ui` gives a host four ready surfaces, split along two axes: **changing how one observes** or **showing what someone already decided**, and **data views** or **dashboards**.

| | Data views (records and analyses) | Dashboards |
|---|---|---|
| Workbench: list, edit, save | `DataWorkbench` | `DashboardWorkbench` |
| Embed: read-only, inside a business page | `EmbeddedView` | `EmbeddedDashboard` |

A workbench lets a user change how they observe; an embed lets a page show what someone already decided, so an embed has no view list, no condition editor and no save — nothing it does is written anywhere. They are split by resource for the same reason: a host that embeds a board says so, and a record view handed to the board's entry is refused as a view it cannot show.

All four may leave out `engine`, `messages` and `locale`: they take the [`ViewHost`](./host#api-ViewHost)'s above.

<!-- typecheck: skip — a JSX fragment; the host is on the host wiring page -->

```tsx
<DataWorkbench definitionId="orders" />
```

## DataWorkbench {#api-DataWorkbench}

The workbench of one data definition: its record views and analysis views in one list, which the user switches between as between any two views.

| Prop | Role |
|---|---|
| `definitionId` | The definition to draw |
| `instanceId`, `onInstanceChange` | Which view is open, as `value` is on an input: leaving it out lets the workbench own it from the effective default on, and passing it — a string, or `null` for that default — puts a host's route in charge, every later change opening what it names. It goes through the leave guard, so a pushed view never takes an unsaved draft away without asking. `onInstanceChange` reports in the same vocabulary, so a host puts it straight into a route |
| `viewKinds` | Which kinds it lists and draws, in the order the "new view" menu offers them. Both by default; naming one is a real narrowing — the other kind is neither listed nor openable there |
| `record` | What the host says about the record views ([`RecordViewProps`](#api-RecordViewProps)): business actions, how a cell is read, whether rows can be picked, what an empty result says. The binding (`bind`'s `actions`, `slots`, `reading`) is the default of each; one given here wins |
| `features` | Which of the workbench's own controls are on screen: `export`, `layouts`, `columns`, `sort`, `search`, `visualization`, `manage`. All on by default; one turned off is absent, not disabled |
| `templates` | What a new view of each kind starts from: `defaultRecordConfig` and `defaultAnalysisConfig` when left out. The view still opens unsaved, and the first save asks for its name and audience |
| `handOver` | A view a dashboard or an embed handed the host's route, opened here as it came: the saved view with what the reader set on the board among its conditions ("Modified", each removable, "Restore" takes them off), or a view nobody saved. Each new object opens once, through the leave guard |
| `onNavigate` | The way back to the board a view was handed from: with it, the workbench draws "Back to ⟨dashboard⟩" under the title bar. The `ViewHost`'s route when left out |
| `expandable` | Whether it offers to fill the screen; on by default |
| `landmark` | The landmark the work column is: `main` by default; `region` where the page already has its own `<main>` |
| `defaultSidebarOpen`, `onSidebarOpenChange` | Where the sidebar starts: view state, never saved and never asked about by the leave guard. Left out, a column narrower than `md` opens folded |
| `density`, `preset`, `theme`, `tokens` | The density, a pinned preset, the mode, and the host's own `--fve-*` for this surface and its popups |
| `onRenderFailure` | Told of a render failure one of its boundaries caught; the part shows a recoverable error state in place regardless |

```ts
export interface DataWorkbenchProps {
  defaultSidebarOpen?: boolean;
  definitionId: string;
  density?: ViewDensity;
  engine?: ViewEngine;
  expandable?: boolean;
  features?: WorkbenchFeatures;
  handOver?: ViewHandOver | null;
  instanceId?: string | null;
  landmark?: WorkbenchLandmark;
  locale?: string;
  messages?: ViewMessages;
  onInstanceChange?(id: string | null): void;
  onNavigate?(to: ViewNavigation): void;
  onRenderFailure?: RenderFailureHandler;
  onSidebarOpenChange?(open: boolean): void;
  optionsFor?(remote: string): FieldOption[] | undefined;
  preset?: ViewPreset;
  record?: RecordViewProps;
  templates?: { record?: RecordViewConfig; analysis?: AnalysisViewConfig };
  theme?: ViewTheme;
  tokens?: ViewSurfaceProps['tokens'];
  viewKinds?: readonly DataViewKind[];
}
export declare function DataWorkbench(props: DataWorkbenchProps): import('react').JSX.Element;
```

### RecordViewProps {#api-RecordViewProps}

What a host says about the record views a workbench draws. They are one object because none of them means anything to an analysis view (an analysis view has no business actions).

| Prop | Role |
|---|---|
| `actions`, `slots` | Declared actions and the host's own markup (see [declared actions](./host#api-actions)) |
| `detail` | The record detail: which record is open, for a host that keeps it in its address, and the host's own sections. Left out, the detail is the workbench's own — a row opens it, its close closes it — holding the definition's field groups alone |
| `renderCell` | Renders one cell of the table, everything else kept. Fall back to `cellValue` for the cells it has nothing special to say about, and enum labels, the zone and number formats keep working |
| `selectable` | Whether rows can be picked; on by default. A host with nothing to do with a selection turns it off rather than show a column of checkboxes that lead nowhere |
| `emptyTitle`, `emptyDescription`, `emptyAction` | The empty result in the host's own words; `emptyAction` is what its one button does, `null` no button |
| `onExported` | Told whenever an export has been handed to the browser — the file's name, its contents and how many rows of which scope. A host that audits what leaves the application reads it |

```ts
export interface RecordViewProps {
  actions?: RecordActions;
  detail?: RecordDetailOptions;
  emptyAction?: (() => void) | null;
  emptyDescription?: string;
  emptyTitle?: string;
  onExported?(file: ExportedFile): void;
  renderCell?(cell: RecordCell): import('react').ReactNode;
  selectable?: boolean;
  slots?: RecordActionSlots;
}
```

## DashboardWorkbench {#api-DashboardWorkbench}

The workbench of one dashboard definition: browse, edit and save boards. A board's filter values are the reader's and never the board's config — a host that wants them in its address listens to `onFiltersChange` and `onTabChange`; the package never touches the address itself.

| Prop | Role |
|---|---|
| `instanceId`, `onInstanceChange` | As on `DataWorkbench` |
| `initialTab`, `onTabChange` | The tab the board opens on, as a host's route has it; left out, or a tab the board does not have, the board opens where its reader last read it |
| `initialFilters`, `onFiltersChange` | What the filters hold as the board opens, as a host's address has them; left out, every filter starts at its default, and what the board does not take is left out |
| `onNavigate` | Every way off the board goes through it — "Open in workbench" on a panel, the follow-up menu on a group, a panel's custom destination. Without it none of these exist: a press on a group does nothing unless the panel cross-filters |
| `features` | `manage` (the view manager) and `export` (a record panel's "Export data…") only; a board whose data must not leave the page turns `export` off |
| `template` | What a new board starts from; an empty one when left out |

The other props (`definitionId`, `expandable`, `landmark`, the sidebar, the look, `onRenderFailure`) are those of `DataWorkbench`.

```ts
export interface DashboardWorkbenchProps {
  defaultSidebarOpen?: boolean;
  definitionId: string;
  density?: ViewDensity;
  engine?: ViewEngine;
  expandable?: boolean;
  features?: Pick<WorkbenchFeatures, 'manage' | 'export'>;
  initialFilters?: DashboardFilters | null;
  initialTab?: string | null;
  instanceId?: string | null;
  landmark?: WorkbenchLandmark;
  locale?: string;
  messages?: ViewMessages;
  onFiltersChange?(filters: DashboardFilters): void;
  onInstanceChange?(id: string | null): void;
  onNavigate?(to: ViewNavigation): void;
  onRenderFailure?: RenderFailureHandler;
  onSidebarOpenChange?(open: boolean): void;
  onTabChange?(tabId: string | null): void;
  optionsFor?(remote: string): FieldOption[] | undefined;
  preset?: ViewPreset;
  template?: DashboardViewConfig;
  theme?: ViewTheme;
  tokens?: ViewSurfaceProps['tokens'];
}
export declare function DashboardWorkbench(props: DashboardWorkbenchProps): import('react').JSX.Element;
```

## What both embeds take {#api-EmbedBaseProps}

Everything an embed drops is chrome; admission, paging, auto-refresh and the request budget are the runtime's, identical to the workbench's.

| Prop | Role |
|---|---|
| `instanceId` | The saved view or board to show; a code-declared system one works too |
| `interaction` | How far the reader may go ([`EmbedInteraction`](#api-EmbedInteraction)); `static` by default |
| `withTitle`, `headingLevel` | Whether its title is drawn as a heading at `headingLevel` (off by default: a page usually names what it embeds in its own words); the level is `2` by default, since only the host knows its outline |
| `autoRefresh` | Whether it refreshes itself on the interval its author saved; on by default. Off, the timer never runs and nothing on screen offers it back |
| `expandable` | "Fill the screen" in the first row, in the interactive tier; off by default, and never in the static tier |
| `openInWorkbench` | Whether "Open in workbench" is offered in the interactive tier, with a route to go by; on by default, and never in the static tier |
| `onNavigate` | The host's route: "Open in workbench", the follow-up menu on a group and a panel's destination go through it. Without it — here, or the `ViewHost`'s `router` or `navigate` — none of them exist |
| `size` | How tall it is: `content` (the default) or `fill` |
| `ref` | The surface it draws on; a host that wants the fill-the-screen control in its own chrome points `useViewExpansion` at it |

```ts
export interface EmbedBaseProps {
  autoRefresh?: boolean;
  className?: string;
  density?: ViewDensity;
  engine?: ViewEngine;
  expandable?: boolean;
  headingLevel?: PanelHeadingLevel;
  instanceId: string;
  locale?: string;
  messages?: ViewMessages;
  onNavigate?(to: ViewNavigation): void;
  onRenderFailure?: RenderFailureHandler;
  openInWorkbench?: boolean;
  preset?: ViewPreset;
  ref?: import('react').Ref<HTMLDivElement>;
  size?: EmbedSize;
  theme?: ViewTheme;
  tokens?: ViewSurfaceProps['tokens'];
  withTitle?: boolean;
}
```

### Interaction tiers {#api-EmbedInteraction}

Neither tier writes anything: an embed never saves a view, a board or a preference.

- `static` — what the page shows, as its author saved it: the rows, the chart or the board and what they were fetched under; nothing on it filters, sorts, pages, redraws or leads anywhere. The default, since a business page shows what someone else set up.
- `interactive` — the reader may look closer, for this viewing only: change a board's filters, sort by a header, page, switch an analysis between table and chart, press a group (the follow-up menu, a board's cross-filter), fill the screen where the host offers it, and open the view in the workbench. None of it is saved; the ways off the page go through the host's route.

```ts
export type EmbedInteraction = 'static' | 'interactive';
```

## EmbeddedView {#api-EmbeddedView}

One saved record or analysis view inside a business page: the result, and what the host switched on around it.

| Prop | Role |
|---|---|
| `scopeFilter` | An outer condition ANDed onto the view's own, in the view's field names: the page's narrowing, locked — the reader sees it in the applied band and cannot take it off. It is admitted like a user's own filter, so a host cannot widen a view past what its definition allows, and it never reaches the saved config |
| `withSearch` | The view's search box at the end of the applied band, where the definition declares a search field (off by default). Record views and the interactive tier only |
| `withExport` | The export button and window in the first row (off by default). Record views and the interactive tier only; with it, rows can be picked, since the window offers to take the picked ones |
| `detail` | A record's detail, opened from its row (off by default): `true` opens it as the host reads the definition's records (`bind`'s `reading`), its open record this embed's own, written to no address; `RecordDetailOptions` lets the host hold which record is open and add sections. Record views and the interactive tier only; read-only |
| `rowActions` | What the host offers on one row of a record view; there is no view to command here, so the slot takes the row alone |

Neither `scopeFilter` nor a locked board filter is a **security boundary**: they decide what the page shows, not what the reader is allowed to read — authorization is the server's.

```ts
export interface EmbeddedViewProps extends EmbedBaseProps {
  detail?: boolean | RecordDetailOptions;
  interaction?: EmbedInteraction;
  rowActions?(row: RecordRow): import('react').ReactNode;
  scopeFilter?: FilterTree | null;
  withExport?: boolean;
  withSearch?: boolean;
}
export declare function EmbeddedView(given: EmbeddedViewProps): import('react').JSX.Element;
```

## EmbeddedDashboard {#api-EmbeddedDashboard}

One saved dashboard inside a business page: the board, its filter bar with each filter in the mode the page gives it, and what the host switched on. It reads the board and never writes: no "Edit", no save, no "Save as", no preference — what the reader changes lives in this viewing alone. Building a board is `DashboardWorkbench`'s.

| Prop | Role |
|---|---|
| `filterModes` | How each of the board's filters is offered, by name: `adjustable` (the default, on the bar, the reader's), `locked` (on the bar as what it holds, fixed), `hidden` (not on the bar, still narrowing what it is wired to). A locked or hidden filter holds what `pageValues` gives it, or its default |
| `groupingMode` | The time grouping's mode, likewise; `adjustable` when left out |
| `pageValues` | What the page holds: the value of each locked or hidden filter and, with `groupingMode` locked or hidden, the time grouping's unit. In force from the first query and followed as it changes — a customer page moving to the next customer. It is the page's own, never the address's (a reader can edit an address) |
| `initialFilters`, `onFiltersChange` | What the reader's filters open at, from the host's address, and told as they change — the adjustable filters only, since a locked or hidden value is the page's |
| `initialTab`, `onTabChange` | The tab it opens on, and told as it changes |
| `withPanelTitles` | Whether the panels' titles are drawn; on by default |
| `withRefresh` | "Updated 10:32" in the first row and, in the interactive tier, the refresh button beside it (off by default); it offers no interval, which stays the author's and `autoRefresh`'s |
| `withExport` | "Export data…" in a panel's "⋯" (off by default); no effect in the static tier |
| `caption` | What the board's numbers are read as, in the host's words, drawn under the title while the board fills the screen; on the page the host draws it itself beside its own title |

```ts
export interface EmbeddedDashboardProps extends EmbedBaseProps {
  caption?: import('react').ReactNode;
  filterModes?: Readonly<Record<string, DashboardFilterMode>>;
  groupingMode?: DashboardFilterMode;
  initialFilters?: DashboardFilters | null;
  initialTab?: string | null;
  interaction?: EmbedInteraction;
  onFiltersChange?(filters: DashboardFilters): void;
  onTabChange?(tabId: string | null): void;
  pageValues?: DashboardFilters | null;
  withExport?: boolean;
  withPanelTitles?: boolean;
  withRefresh?: boolean;
}
export declare function EmbeddedDashboard(given: EmbeddedDashboardProps): import('react').JSX.Element;
```

## The full working version

- Storybook: [record workbench](/storybook/?path=/docs/view-engine-组件状态-记录工作台--docs), [dashboard](/storybook/?path=/docs/view-engine-组件状态-仪表盘--docs), [EmbeddedView](/storybook/?path=/docs/view-engine-组件状态-embeddedview--docs), [EmbeddedDashboard](/storybook/?path=/docs/view-engine-组件状态-embeddeddashboard--docs).
- Source files: [`DataWorkbench.tsx`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/src/ui/DataWorkbench.tsx), [`DashboardWorkbench.tsx`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/src/ui/DashboardWorkbench.tsx), [`EmbeddedView.tsx`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/src/ui/EmbeddedView.tsx), [`EmbeddedDashboard.tsx`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/src/ui/EmbeddedDashboard.tsx), [`embed/options.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/src/ui/embed/options.ts).
