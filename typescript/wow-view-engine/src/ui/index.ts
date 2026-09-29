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
export {
  type AlertTone,
  LineAlert,
  type LineAlertProps,
  TONE_ICON,
} from './alerts.js';
export { AnalysisChart, type AnalysisChartProps } from './AnalysisChart.js';
export {
  registerChartMap,
  type ChartMapGeoJson,
  type ChartMapSource,
} from './charts/maps.js';
export { AnalysisTable, type AnalysisTableProps } from './AnalysisTable.js';
export { AppliedBar, type AppliedBarProps } from './AppliedBar.js';
export { BulkStatus, type BulkStatusProps } from './BulkStatus.js';
export { ColumnSettings, type ColumnSettingsProps } from './ColumnSettings.js';
export {
  PanelGridItem,
  type PanelGridItemProps,
  PanelHandle,
  type PanelHandleProps,
  PanelOrder,
  type PanelOrderProps,
  PanelResizeHandle,
  type PanelResizeHandleProps,
} from './DashboardArrange.js';
export { DashboardGrid, type DashboardGridProps } from './DashboardGrid.js';
export {
  DashboardPanel,
  type DashboardPanelProps,
  type PanelHeadingLevel,
  panelName,
  panelNames,
} from './DashboardPanel.js';
export {
  ContentPanel,
  type ContentPanelProps,
  HeadingPanel,
  type HeadingPanelProps,
  ImagePanel,
  type ImagePanelProps,
  LinksPanel,
  type LinksPanelProps,
  MarkdownPanel,
  type MarkdownPanelProps,
} from './DashboardPanels.js';
export {
  type DashboardEditExtensions,
  DashboardEditExtensionsContext,
  type NewPanelSpot,
  useDashboardEditExtensions,
} from './dashboard/extensions.js';
export {
  DashboardWorkbench,
  type DashboardWorkbenchProps,
} from './DashboardWorkbench.js';
// Building a board, batch B3 (D22 C–E): the tab bar, the commands a board's
// menus call and the dialogs they open.
export {
  DashboardTabs,
  type DashboardTabsProps,
  tabTitle,
} from './dashboard/DashboardTabs.js';
export {
  type DashboardExtensionsOptions,
  useDashboardExtensions,
} from './dashboard/building.js';
export {
  type NewAnalysis,
  NewAnalysisDialog,
  type NewAnalysisDialogProps,
} from './dashboard/NewAnalysisDialog.js';
export {
  PresentationDialog,
  type PresentationDialogProps,
} from './dashboard/PresentationDialog.js';
export { describeConfig } from './describeConfig.js';
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
 * stands. The two context arguments come from `useViewMessages` and
 * `useSurfaceDisplay`, so a host reads them off the surface it is inside
 * rather than passing a language around. The last one is the `CellSurface`
 * it is drawing on — `'table'` or `'card'` — which decides how many lines a
 * value may take and nothing else.
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
} from './display.js';
export { type DownloadedFile, downloadFile, fileName } from './download.js';
export {
  EditorBand,
  type EditorBandProps,
  EditorBandToggle,
  type EditorBandToggleProps,
  EditorFold,
  type EditorFoldProps,
} from './EditorBand.js';
export {
  EmbeddedDashboard,
  type EmbeddedDashboardProps,
} from './EmbeddedDashboard.js';
export type {
  BoardFilterModes,
  DashboardFilterMode,
} from './dashboard/filterModes.js';
export { EmbeddedView, type EmbeddedViewProps } from './EmbeddedView.js';
export type {
  EmbedBaseProps,
  EmbedInteraction,
  EmbedSize,
} from './embed/options.js';
export {
  ExportButton,
  ExportDialog,
  type ExportDialogProps,
  type ExportOffer,
  type ExportWindowProps,
} from './ExportDialog.js';
export {
  FilterPanel,
  type FilterPanelProps,
  crossesBoundary,
  leavesEditor,
} from './FilterPanel.js';
export {
  type MessageKey,
  type ViewMessages,
  defaultMessages,
  formatIssue,
  formatIssues,
  formatMessage,
} from './messages.js';
// The catalogues ship beside the formatters so a host can compose one:
// `{ ...zhCN, 'label.filter.apply': '确定' }`. `zh-CN` is a leaf module no
// component imports, so a bundle that only pulls in components drops it.
export { en } from './messages/en.js';
export { zhCN } from './messages/zh-CN.js';
export {
  type MessageFormatters,
  MessagesProvider,
  type MessagesProviderProps,
  type Say,
  useSay,
  useViewMessages,
} from './MessagesProvider.js';
export {
  FilterValueEditor,
  type FilterValueEditorProps,
} from './FilterValueEditor.js';
export { NumberInput } from './filter/inputs/number.js';
export {
  AUDIENCE_ICON,
  KIND_ICON,
  SYSTEM_ICON,
  SurfaceKind,
  kindIssue,
  kindWord,
  useKindIssue,
  useKindWord,
} from './kinds.js';
export { LeaveDialog, type LeaveDialogProps } from './LeaveGuard.js';
export { RecordCards, type RecordCardsProps } from './RecordCards.js';
export {
  RecordPagination,
  type RecordPaginationProps,
} from './RecordPagination.js';
export {
  type RecordCell,
  RecordTable,
  type RecordTableProps,
} from './RecordTable.js';
export {
  type DataViewKind,
  DataWorkbench,
  type DataWorkbenchProps,
} from './DataWorkbench.js';
export { type WorkbenchFeatures, featuresOf } from './features.js';
export {
  RenderBoundary,
  type RenderBoundaryName,
  type RenderBoundaryProps,
  type RenderFailure,
  type RenderFailureHandler,
  RenderSlot,
} from './RenderBoundary.js';
export {
  BUILT_IN_PRESETS,
  type BuiltInPreset,
  type ViewDensity,
  type ViewPreset,
} from './presets.js';
// The theme's contract as a type: every `--fve-*` a host may set (the
// registry itself is not public; its machine form is `theme-tokens.json`).
export type { FveToken } from './theme/tokens.js';
export { RefreshControl, type RefreshControlProps } from './RefreshControl.js';
export {
  LAYOUT_LABEL,
  ResultToolbar,
  type ResultToolbarProps,
} from './ResultToolbar.js';
export { RowActions, type RowActionsProps } from './RowActions.js';
export {
  SaveActions,
  type SaveActionsProps,
  SharedSaveConfirm,
  UnsavedMark,
} from './SaveActions.js';
export {
  type SaveAsCommands,
  SaveAsDialog,
  type SaveAsDialogProps,
} from './SaveAsDialog.js';
export {
  type SortOwner,
  SortSettings,
  type SortSettingsProps,
} from './SortSettings.js';
export {
  ErrorStrip,
  type ErrorStripProps,
  type IssueStripProps,
  NoteStrip,
  QueryStrip,
  type QueryStripProps,
  StatusStrip,
  type StatusStripProps,
  WarningStrip,
  dedupeIssues,
} from './StatusStrip.js';
// The hook ships beside the toggle: an embed has no title bar to put a
// button in, so a host that wants its embed to fill the screen owns the
// control and points this at the surface it got a ref to.
export {
  ViewExpandExit,
  ViewExpandToggle,
  type ViewExpandToggleProps,
  type ViewExpansion,
  useViewExpansion,
} from './ViewExpansion.js';
export {
  RevertDialog,
  type RevertDialogProps,
  ViewHeader,
  type ViewHeaderProps,
  type ViewHeaderState,
} from './ViewHeader.js';
export { ViewList, type ViewListProps } from './ViewList.js';
export { ViewSwitcher, type ViewSwitcherProps } from './ViewSwitcher.js';
export { ViewManager, type ViewManagerProps } from './ViewManager.js';
export {
  ViewEngineProvider,
  type ViewEngineProviderProps,
} from './ViewEngineProvider.js';
export { bind, type ViewBinding, type ViewBindingOptions } from './bindings.js';
export type {
  RoutedTarget,
  ViewDestination,
  ViewRoute,
  ViewRouteOf,
  ViewRouteState,
} from '../runtime/routes.js';
export {
  ViewSurface,
  type ViewSurfaceProps,
  type ViewTheme,
  useSurfaceAttributes,
  useSurfaceDisplay,
  useSurfaceFont,
  useSurfaceHostTokens,
  useSurfaceTheme,
  useSurfaceTokens,
} from './ViewSurface.js';
export {
  type WorkbenchLandmark,
  WorkbenchShell,
  type WorkbenchShellProps,
} from './WorkbenchShell.js';
export {
  NO_PARTS,
  type RenderParts,
  type WorkbenchParts,
} from './workbench/parts.js';
export {
  type RecordDetailOptions,
  RecordParts,
  type RecordPartsProps,
  type RecordViewProps,
} from './workbench/RecordParts.js';
export type { ExportedFile } from './record/exportOffer.js';
export {
  type AnalysisHost,
  AnalysisParts,
  type AnalysisPartsProps,
} from './workbench/AnalysisParts.js';
export {
  type NewViewCommand,
  NewViewControl,
  NewViewItem,
} from './workbench/NewView.js';
export {
  type ViewWriteCallbacks,
  WriteOutcome,
  type WriteOutcomeProps,
} from './WriteOutcome.js';
