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
 * Root entry of `@ahoo-wang/wow-view-engine`.
 *
 * The headless layers, in the order `docs/design/` fixes: `model`, `filter`,
 * `record` / `analysis` / `dashboard`, `runtime`, `store`. React lives in
 * the `/react` and `/ui` entries of its own. Dependency rules between the
 * layers are enforced by `test/architecture.test.ts`.
 *
 * Every public name is written here by name, grouped by the file that
 * declares it, and no entry re-exports a whole module (D64): an `export` a
 * file writes for its neighbours stays inside the package until a line
 * here makes it a promise.
 */
// model — the definition and config types, and their constants.
export {
  type AnalysisColumn,
  type DateDiffUnitName,
  type AggregationDatePartName,
  type AggregationDateUnitName,
  type AnalysisDerivedExpression,
  type AnalysisElement,
  type AnalysisExpression,
  type AggregationExpressionOperatorName,
  type AggregationFunctionName,
  type AnalysisGroup,
  type AggregationGroupTypeName,
  type AnalysisHavingExpression,
  type AnalysisLayout,
  type AnalysisMetric,
  type AnalysisNamed,
  type AnalysisSort,
  type AnalysisTableSpec,
  type AnalysisTop,
  type AnalysisViewConfig,
  type DerivedFormat,
  type FieldMetricType,
  type ValueMetric,
} from './model/analysis.js';
export {
  type AxisSpec,
  type BoxplotSpec,
  CHART_TYPES,
  type CalendarSpec,
  type CandlestickSpec,
  type CartesianMissing,
  type CartesianSeries,
  type CartesianSpec,
  type ChartFamily,
  type ChartSpec,
  type ChartType,
  type DerivedKind,
  type DerivedSeries,
  type FunnelSpec,
  type FunnelStages,
  type GaugeSpec,
  type HeatmapSpec,
  type HierarchySpec,
  type MapSpec,
  type MetricAgainst,
  type MetricCardSpec,
  type MetricHeadline,
  type MetricTrend,
  type ParallelSpec,
  type PieSpec,
  type RadarSpec,
  type ReferenceBand,
  type ReferenceLine,
  type ReferenceStatistic,
  type SankeySpec,
  type ScatterSpec,
  type ThemeRiverSpec,
  type TreemapSpec,
  type ValueFormat,
  type WaterfallSpec,
} from './model/chart.js';
export {
  type ConfigOfKind,
  type DataViewConfig,
  type DataViewConfigBase,
  type RefreshConfig,
  type ViewConfig,
  type ViewConfigBase,
  type ViewKind,
} from './model/config.js';
export type { CurrencyReading } from './model/currency.js';
export {
  type BoardValueSource,
  type ContentPanelKind,
  type DashboardContentPanel,
  type DashboardField,
  type DashboardFilterType,
  type DashboardFilters,
  type DashboardLink,
  type DashboardPanel,
  type DashboardPanelBase,
  type DashboardTab,
  type DashboardTimeGrouping,
  type DashboardViewConfig,
  type DashboardViewPanel,
  type DashboardWidth,
  type OwnedView,
  type PanelBinding,
  type PanelClick,
  type PanelLayout,
  type PanelPresentation,
  type DashboardViewPanelBase,
} from './model/dashboard.js';
export {
  type AggregationFieldCapability,
  type AnalysisCapability,
  type AnalysisElementCapability,
  type AnalysisLimits,
  type AnalysisSpec,
  type WindowFunctionName,
  type DashboardDefinition,
  type DataViewDefinition,
  type DefinitionDescribed,
  type DefinitionNarrowing,
  type FieldAnalysisSpec,
  type FieldGroupDefinition,
  type OpenCapabilities,
  type RecordCapability,
  type SystemView,
  type ViewDefinition,
  isSystemInstanceId,
  systemInstanceId,
} from './model/definition.js';
export {
  type BuiltinFieldKindId,
  type DateTemporal,
  type DecimalNumeric,
  type EpochTemporal,
  type EpochTimeUnit,
  type FieldCellId,
  type FieldDefinition,
  type FieldKindId,
  type FieldNumeric,
  type FieldOption,
  type FieldTemporal,
  type FieldTone,
  type FixedMoneyNumeric,
  type MetadataFieldKindId,
  type NumberFormat,
  type RowMoneyNumeric,
  type SearchModeName,
  type StringComparisonName,
  type SummaryFunction,
  type TimePrecision,
} from './model/field.js';
export {
  type FilterGroup,
  type FilterGroupOperator,
  type FilterLeaf,
  type FilterMode,
  type FilterNode,
  type FilterOperatorName,
  type FilterTree,
  type FilterValue,
} from './model/filter.js';
export {
  MAX_VIEW_CONFIG_BYTES,
  MAX_VIEW_TITLE_LENGTH,
  type ViewAudience,
  type ViewInstance,
  type ViewInstanceSummary,
  type ViewPreferences,
  type ViewScope,
} from './model/instance.js';
export type { Issue, IssuePath, IssueSeverity } from './model/issue.js';
export type { JsonValue, LiteralEnums } from './model/json.js';
export { DEFAULT_RUNTIME_LIMITS, type RuntimeLimits } from './model/limits.js';
export {
  type RecordPagingMode,
  type RecordCardSpec,
  type RecordColumn,
  type RecordData,
  type RecordKanbanSpec,
  type RecordKey,
  type RecordLayout,
  type RecordPageTarget,
  type RecordSort,
  type RecordSummary,
  type RecordTableSpec,
  type RecordViewConfig,
  type SortDirectionName,
} from './model/record.js';
export {
  type ConflictingState,
  VIEW_STORE_ERROR_CODES,
  ViewStoreError,
  type ViewStoreErrorCode,
  isViewStoreError,
} from './model/storeError.js';
export {
  type Text,
  type TextResolver,
  type TextWords,
  text,
  textKeyOf,
  withText,
} from './model/text.js';
// filter — the filter kernel and the `FieldKind` registry.
export { type FilterCompileContext, compileFilter } from './filter/compile.js';
export {
  type FieldKindDescription,
  type FilterSummaryItem,
  type FilterSummaryRelation,
  type FilterSummaryValue,
} from './filter/describe.js';
export {
  EDITOR_INPUTS,
  type EditorDescriptor,
  type EditorInput,
  type FieldKind,
  type FieldKindBlankContext,
  type FieldKindCompileContext,
  type FieldKindDescribeContext,
  type FieldKindRegistry,
  type FieldKindValidateContext,
  type NestedTree,
  createFieldKindRegistry,
  issue,
  withFieldKinds,
} from './filter/fieldKind.js';
export {
  BUILTIN_FIELD_KINDS,
  builtinFieldKinds,
} from './filter/kinds/index.js';
export { type ArrayFilterValue } from './filter/kinds/array.js';
export { type InstantRange, resolveDateTimeRange } from './filter/time.js';
export { type FilterPath } from './filter/tree.js';
export {
  type ValidateFilterOptions,
  validateFilter,
} from './filter/validate.js';
export {
  type AbsoluteDateTimeValue,
  type BooleanFilterValue,
  type DateTimeFilterValue,
  type DateTimePreset,
  type ComparisonOperatorName,
  type DurationFilterValue,
  type EnumFilterValue,
  type NumberFilterValue,
  type NumberRange,
  type PresetDateTimeValue,
  type ReferenceFilterValue,
  type ReferenceItem,
  type RelativeDateDirection,
  type RelativeDateTimeValue,
  type RelativeDateUnit,
  type StringFilterValue,
  isDateTimeFilterValue,
} from './filter/values.js';
// record — the record kernel.
export { compileRecord } from './record/compile.js';
export { defaultRecordConfig } from './record/defaults.js';
export { type DetailSection } from './record/detail.js';
export {
  type CsvOptions,
  type ExportColumn,
  type ExportFormat,
  serializeCsv,
} from './record/export.js';
export {
  type CursorPaging,
  type PagedPaging,
  type RecordPaging,
} from './record/paging.js';
export {
  type ColumnEdge,
  type ElementTitleView,
  type RecordCardField,
  type RecordCardView,
  type RecordColumnView,
  type RecordLaneView,
  type RecordRow,
  type RecordProjection,
  type SummaryCell,
  type SummaryRow,
  projectRecord,
} from './record/project.js';
export {
  type ValidateRecordOptions,
  validateRecord,
} from './record/validate.js';
// analysis — the analysis kernel.
export {
  type ValueCandidate,
  type ValueCandidates,
} from './analysis/candidates.js';
export {
  type ChartData,
  type HeatmapData,
  type PieData,
  type PieSlice,
  type ScatterData,
  type ShapeContext,
} from './analysis/chart.js';
export {
  type MetricCardData,
  type MetricPeriod,
} from './analysis/metricCard.js';
export type { WaterfallData, WaterfallStep } from './analysis/waterfall.js';
export type { TreemapData, TreemapTile } from './analysis/treemap.js';
export type { BoxplotBox, BoxplotData } from './analysis/boxplot.js';
export type { Candle, CandlestickData } from './analysis/candlestick.js';
export type { GaugeData } from './analysis/gauge.js';
export type { MapData, MapRegion } from './analysis/map.js';
export type {
  CalendarData,
  CalendarDay,
  RiverStream,
  ThemeRiverData,
} from './analysis/timeCharts.js';
export type {
  HierarchyData,
  HierarchyNode,
  SankeyData,
  SankeyLink,
  SankeyNode,
} from './analysis/hierarchy.js';
export type {
  ChartProfile,
  ParallelData,
  RadarData,
} from './analysis/profiles.js';
export type { CartesianData } from './analysis/cartesian.js';
export type {
  CartesianGap,
  DerivedGap,
  DerivedLine,
} from './analysis/derived.js';
export type { PlacedLine, SeriesExtremes } from './analysis/references.js';
export type { FunnelData, FunnelStage } from './analysis/funnel.js';
export { type ChartUnfit } from './analysis/chartFamilies.js';
export { type Picked } from './analysis/chartOptions.js';
export { fitChartSlots } from './analysis/chartSlots.js';
export { compileAnalysis } from './analysis/compile.js';
export {
  DEFAULT_MISSING_KEY,
  defaultAnalysisConfig,
} from './analysis/defaults.js';
export { type AnalysisLimitBounds } from './analysis/limitBounds.js';
export { type ChartFit } from './analysis/fitCharts.js';
export { type Dropped } from './analysis/expand.js';
export { type HavingOperator, type HavingRow } from './analysis/having.js';
export { type MetricCondition } from './analysis/metricCondition.js';
export { type MetricFunction } from './analysis/metricFormat.js';
export {
  type AnalysisColumnView,
  type AnalysisProjection,
  type ColumnCurrency,
  projectAnalysis,
} from './analysis/project.js';
export {
  type ValidateAnalysisOptions,
  validateAnalysis,
} from './analysis/validate.js';
// dashboard — the dashboard kernel.
export { emptyDashboardConfig } from './dashboard/defaults.js';
export {
  type NewContentPanel,
  type NewPanel,
  type NewPanelPlacement,
  type NewViewPanel,
} from './dashboard/edit.js';
export { type NewFilter } from './dashboard/filterEdit.js';
export {
  type ArrangeStep,
  type PanelSize,
  type SizedPanel,
  inRows,
} from './dashboard/layout.js';
export {
  type PanelDefinition,
  type PanelReference,
  type PanelReferences,
  type ValidateDashboardOptions,
  validateDashboard,
} from './dashboard/validate.js';
export { type FilterReach } from './dashboard/wiring.js';
// runtime — the engine, the runtime contracts and the types their
// signatures name; never a part the runtime is built from.
export { ViewEngine } from './runtime/viewEngine.js';
export type {
  ViewEngineOptions,
  ViewResource,
  ViewListing,
} from './runtime/viewEngineOptions.js';
export type { ConflictChoice, WriteTarget } from './runtime/writeLedger.js';
export {
  type ValidateDefinitionOptions,
  validateDefinition,
} from './runtime/validateDefinition.js';
export {
  type DefineViewOptions,
  defineView,
} from './runtime/define/defineView.js';
export {
  type DefineDatasetSpec,
  defineDataset,
} from './runtime/define/defineDataset.js';
export {
  type DatasetCatalog,
  type DatasetDuplicate,
  type DatasetDirectoryEntry,
  type DatasetOverrides,
  type DatasetRefresh,
  type DatasetRegistrar,
  type DatasetResource,
  DatasetResources,
  type DatasetResourcesInit,
  type DatasetWordingEditor,
  type DatasetWordingStore,
  type StoredDatasetWording,
} from './runtime/datasetResources.js';
export type {
  DefineViewSpec,
  FieldSpec,
  OptionSpec,
} from './runtime/define/spec.js';
export {
  type AnalysisViewRuntime,
  type AnyViewRuntime,
  type CreateInput,
  type DefinitionFor,
  type OpenOptions,
  type QueryStatus,
  type RecordViewRuntime,
  type RuntimeFor,
  type ViewQueryState,
  type ViewResult,
  type ViewRuntime,
  type ViewRuntimeState,
  type SaveAsInput,
  hasAsked,
  hasResult,
  isAnalysisRuntime,
} from './runtime/viewRuntimeTypes.js';
export { isRecordRuntime } from './runtime/recordRuntime.js';
export type {
  RoutedTarget,
  ViewDestination,
  ViewLocation,
  ViewRoute,
  ViewRouteOf,
  ViewRouteState,
  ViewRouter,
} from './runtime/routes.js';
export {
  type ActionConfirm,
  type ActionContext,
  type ActionForm,
  type ActionFormField,
  type ActionInput,
  type ActionPlace,
  type ActionTone,
  type Availability,
  type RecordAction,
  type RecordActions,
  type RefusalGroup,
  actions,
  type ActionRefusal,
  type ActionRow,
} from './runtime/actions.js';
export {
  ExportCancelledError,
  type ExportRowsOptions,
  type ExportedRows,
  isExportCancelledError,
} from './runtime/exportRows.js';
export type { ValueCandidateSource } from './runtime/valueCandidates.js';
export type {
  DashboardRuntime,
  DashboardRuntimeState,
  HeldFilters,
} from './runtime/dashboard/contract.js';
export type { DashboardPanelState } from './runtime/dashboard/panels.js';
export type {
  DashboardEditing,
  DashboardFilterEditing,
} from './runtime/dashboard/editing.js';
export type {
  EditCommand,
  EditHistoryState,
  EditStep,
} from './runtime/dashboard/history.js';
export type { PanelGrouping } from './runtime/dashboard/grouping.js';
export type {
  CrossFilterOutcome,
  DestinationBoard,
  PressDestination,
} from './runtime/dashboard/press.js';
export type {
  BoardOrigin,
  DashboardTarget,
  GroupNaming,
  HandOver,
  SavedViewTarget,
  UnsavedViewTarget,
  ViewHandOver,
  ViewNavigation,
} from './runtime/navigation.js';
export {
  ViewCommandError,
  ViewWriteError,
  type WriteAction,
  type WriteHandle,
  type WritePayload,
  type WriteState,
  isViewCommandError,
  isViewWriteError,
} from './runtime/write.js';
export type {
  ViewChange,
  ViewChangeKind,
  ViewChangeListener,
} from './runtime/viewChanges.js';
export {
  type RuntimeEnvironment,
  type ViewErrorContext,
  type ViewErrorEvent,
  type ViewErrorKind,
  type ChartStage,
  type ExportOperation,
  type QueryOperation,
  type StoreOperation,
  type ViewErrorContexts,
  type ViewErrorPlace,
  type VisibilitySource,
  defaultRuntimeEnvironment,
} from './runtime/environment.js';
export type {
  OptionSource,
  ProjectedAnalysis,
  ProjectedBase,
  ProjectedRecord,
  ProjectedView,
  SourceOptions,
  ViewSource,
} from './runtime/source.js';
export {
  ReferenceLabelTimeoutError,
  type ReferenceId,
  type ReferenceLabel,
  type ReferenceLabelReader,
} from './runtime/referenceLabels.js';
// store — the `ViewStore` port, the in-memory one and its local snapshot.
export {
  type LocalStorageSnapshotOptions,
  localStorageSnapshot,
} from './store/localStorageSnapshot.js';
export {
  type MemorySnapshot,
  type MemoryState,
  MemoryViewStore,
  type MemoryViewStoreOptions,
} from './store/MemoryViewStore.js';
export {
  type InstancePermissions,
  type ReadOptions,
  type ViewListOptions,
  type ViewListPage,
  type ViewPermissions,
  type ViewStore,
  type WriteOptions,
  emptyPreferences,
} from './store/ViewStore.js';
export type { DefinitionLookup } from './dashboard/declared.js';
export type { MetricMove, MetricMoveStop } from './analysis/metricOrder.js';
