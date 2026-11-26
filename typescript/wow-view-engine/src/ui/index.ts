/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * The `/ui` entry: the default look, built on shadcn/ui with Base UI
 * primitives. A component reads what it draws in one of two ways: a pure
 * kernel reading (`filter`, `record`, `analysis`, `dashboard` — functions of
 * a definition and a config, public from the root entry) it may call
 * directly, and a stateful judgement — anything that reads an open runtime,
 * a write in flight or a command's outcome — it takes from a controller in
 * `/react`. So replacing one is a matter of writing different markup against
 * the same controllers and the same kernel functions.
 *
 * Styles ship separately as `@ahoo-wang/wow-view-engine/styles.css`.
 */
// The one callout recipe — the registry's `Alert`, one line high — so a
// host drawing its own notice above a view can wear the same face.
export { type AlertTone } from './kit/alerts.js';
export {
  AnalysisChart,
  type AnalysisChartProps,
} from './analysis/AnalysisChart.js';
export {
  registerChartMap,
  type ChartMapGeoJson,
  type ChartMapSource,
} from './charts/maps.js';
export {
  AnalysisTable,
  type AnalysisTableProps,
} from './analysis/AnalysisTable.js';
export { AppliedBar, type AppliedBarProps } from './filter/AppliedBar.js';
export {
  DashboardGrid,
  type DashboardGridProps,
} from './dashboard/DashboardGrid.js';
export { type PanelHeadingLevel } from './dashboard/DashboardPanelCard.js';
export {
  HeadingPanel,
  type HeadingPanelProps,
  ImagePanel,
  type ImagePanelProps,
  LinksPanel,
  type LinksPanelProps,
  MarkdownPanel,
  type MarkdownPanelProps,
} from './dashboard/DashboardPanels.js';
export {
  type DashboardEditExtensions,
  DashboardEditExtensionsContext,
  type NewPanelSpot,
} from './dashboard/extensions.js';
export {
  DashboardWorkbench,
  type DashboardWorkbenchProps,
} from './DashboardWorkbench.js';
// The standalone dataset view (D93, Wow D238 ⑨): datasets from one catalog or
// several, picked and opened, and bound into a host's navigation.
export {
  DatasetWorkbench,
  type DatasetWorkbenchProps,
} from './DatasetWorkbench.js';
export {
  type DatasetBindingOptions,
  datasetBindings,
} from './datasetBindings.js';
// Building a board (D22 C–E): what a board's menus offer and the dialog
// they open.
export {
  type DashboardExtensionsOptions,
  useDashboardExtensions,
} from './dashboard/building.js';
export {
  type NewAnalysis,
  NewAnalysisDialog,
  type NewAnalysisDialogProps,
} from './dashboard/NewAnalysisDialog.js';
/**
 * How this package reads one value, so a host that renders a cell itself can
 * fall back to it instead of reimplementing it.
 *
 * `renderCell` is the smallest change a host can make to a record view, and
 * the smallest one has to be the cheapest: a host that overrides one column
 * must not lose the reading of the rest. These three are what the table and
 * the cards themselves call, and they promise exactly what the default cell
 * shows — enum labels from the definition's own options, times on the
 * surface's clock and in its language, numbers in the field's
 * `numberFormat`, booleans in the wording in force.
 *
 * `cellValue` is the node a cell draws (badges, a guarded link, a paragraph);
 * `cellText` is the same reading as one line of text, for a CSV, a copied
 * selection or a `title`; `displayValue` is the kind's own reading alone,
 * `undefined` where the kind has nothing to add and the caller's rendering
 * stands; `tableTime` is a time as a table cell writes it — to the minute,
 * the year only outside the current one — whose whole reading is
 * `displayValue`'s. The two context arguments come from `useViewMessages` and
 * `useSurfaceDisplay`, so a host reads them off the surface it is inside
 * rather than passing a language around. The last one is the `CellSurface`
 * it is drawing on — `'table'`, `'card'` or `'detail'` — which decides how
 * many lines a value may take, and that a table's time is short.
 *
 * The vendored shadcn primitives underneath are deliberately *not* public:
 * what is promised here is the reading of a value, not the markup around it.
 */
export { cellValue, type CellField, type CellSurface } from './record/cells.js';
export {
  cellText,
  displayValue,
  type DisplayContext,
  type DisplayField,
  type ElementField,
  type ElementTitleField,
} from './kit/display.js';
export { EditorBand, type EditorBandProps } from './workbench/EditorBand.js';
export {
  EmbeddedDashboard,
  type EmbeddedDashboardProps,
} from './EmbeddedDashboard.js';
export type {
  DashboardFilterModes,
  DashboardFilterMode,
} from './dashboard/filterModes.js';
export { EmbeddedView, type EmbeddedViewProps } from './EmbeddedView.js';
export type {
  EmbedBaseProps,
  EmbedInteraction,
  EmbedSize,
} from './embed/options.js';
export {
  type ExportOffer,
  type ExportWindowOptions,
} from './kit/ExportDialog.js';
export { FilterPanel, type FilterPanelProps } from './filter/FilterPanel.js';
export {
  type MessageKey,
  type MessageOverrides,
  type ViewMessages,
  formatIssue,
  formatMessage,
} from './kit/messages.js';
// The catalogues ship beside the formatters so a host can compose one:
// `{ ...zhCN, 'label.filter.apply': '确定' }`. One name each, `en` and
// `zhCN` (R2-88); English is what every component reads until a host says
// otherwise. `zh-CN` is a leaf module no component imports, so a bundle
// that only pulls in components drops it.
export { en } from './messages/en.js';
export { zhCN } from './messages/zh-CN.js';
export {
  type MessageFormatters,
  MessagesProvider,
  type MessagesProviderProps,
  type Say,
  useSay,
  useViewMessages,
} from './kit/MessagesProvider.js';
export { LeaveDialog, type LeaveDialogProps } from './workbench/LeaveGuard.js';
export { RecordCards, type RecordCardsProps } from './record/RecordCards.js';
export { RecordKanban, type RecordKanbanProps } from './record/RecordKanban.js';
export { type KanbanMove } from './record/kanbanDrag.js';
export {
  RecordPagination,
  type RecordPaginationProps,
  type RecordPaginationTable,
} from './record/RecordPagination.js';
export {
  type RecordCell,
  RecordTable,
  type RecordTableProps,
} from './record/RecordTable.js';
export {
  type DataViewKind,
  DataWorkbench,
  type DataWorkbenchProps,
} from './DataWorkbench.js';
export type { WorkbenchFeatures } from './kit/features.js';
export {
  type RenderBoundaryName,
  type RenderFailure,
  type RenderFailureHandler,
} from './kit/RenderBoundary.js';
export {
  BUILTIN_PRESETS,
  type BuiltinPreset,
  type ViewDensity,
  type ViewPreset,
} from './kit/presets.js';
// The theme's contract as a type: every `--fve-*` a host may set (the
// registry itself is not public; its machine form is `theme-tokens.json`).
export type { FveToken, TokenName } from './theme/tokens.js';
export {
  ResultToolbar,
  type ResultToolbarProps,
} from './workbench/ResultToolbar.js';
// The words per record layout, which the toolbar's switch and a finding
// about a layout both say.
export { RowActions, type RowActionsProps } from './record/RowActions.js';
export { SaveActions, type SaveActionsProps } from './workbench/SaveActions.js';
export { StatusStrip, type StatusStripProps } from './kit/StatusStrip.js';
// The hook without the toggle: an embed has no title bar to put a button
// in, so a host that wants its embed to fill the screen owns the control
// and points this at the surface it got a ref to (`EmbedBaseProps.ref`).
export { type ViewExpansion, useViewExpansion } from './kit/ViewExpansion.js';
export {
  ViewHeader,
  type ViewHeaderProps,
  type ViewHeaderState,
  type HeadingLevel,
} from './workbench/ViewHeader.js';
export { ViewManager, type ViewManagerProps } from './manage/ViewManager.js';
export { ViewHost, type ViewHostProps } from './ViewHost.js';
export {
  type ColorModeControl,
  type HostColorMode,
  useColorMode,
} from './colorMode.js';
export {
  useViewNavigation,
  type ViewNavigationItem,
  type ViewNavigationView,
} from './viewNavigation.js';
export {
  bind,
  type ViewBinding,
  type ViewBindingOptions,
} from './workbench/bindings.js';
export type {
  RoutedTarget,
  ViewDestination,
  ViewLocation,
  ViewRoute,
  ViewRouteState,
  ViewRouter,
} from '../runtime/routes.js';
export {
  ViewSurface,
  type ViewSurfaceProps,
  type ColorMode,
  useSurfaceDisplay,
} from './kit/ViewSurface.js';
export {
  type WorkbenchLandmark,
  WorkbenchShell,
  type WorkbenchShellProps,
} from './workbench/WorkbenchShell.js';
export {
  type RecordDetailOptions,
  type RecordViewOptions,
} from './workbench/RecordParts.js';
export type { ExportedFile } from './record/exportOffer.js';
export { type ViewWriteCallbacks } from './workbench/WriteOutcome.js';
export type { ChartImageSlot } from './charts/image.js';
export type { EmptyWayOut } from './record/emptyWayOut.js';
export type { FinalFocus } from './kit/focus.js';
export type { HeaderSorting } from './analysis/headerSort.js';
export type { OnPick } from './charts/family.js';
export type { ReleasedPins } from './record/pinCap.js';
export type { SelectionContext } from './record/SelectionBar.js';
export type { PickAnchor } from './kit/anchor.js';
