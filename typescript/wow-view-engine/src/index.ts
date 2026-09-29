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
  ANALYSIS_AUTO_RUN_MEMBERS,
  ANALYSIS_DATE_DIFF_UNITS,
  ANALYSIS_DATE_PARTS,
  ANALYSIS_DATE_UNITS,
  ANALYSIS_PRESENTATION_MEMBERS,
  type AnalysisColumn,
  type AnalysisDateDiffUnit,
  type AnalysisDatePart,
  type AnalysisDateUnit,
  type AnalysisDerivedExpression,
  type AnalysisElement,
  type AnalysisExpression,
  type AnalysisExpressionOperator,
  type AnalysisFunction,
  type AnalysisGroup,
  type AnalysisGroupType,
  type AnalysisHavingExpression,
  type AnalysisLayout,
  type AnalysisMetric,
  type AnalysisNamed,
  type AnalysisSort,
  type AnalysisTableSpec,
  type AnalysisViewConfig,
  DATE_PART_DOMAINS,
  DERIVED_FORMAT_STYLES,
  type DatePartOffer,
  type DerivedFormat,
  FIELD_METRIC_TYPES,
  type FieldMetricType,
  MAX_DERIVED_DECIMALS,
  type ValueMetric,
  dateDiffUnitsOf,
  datePartsOf,
  expressionFieldsOf,
  groupFieldsOf,
  isValueMetric,
} from './model/analysis.js';
export {
  type AxisSpec,
  type BoxplotSpec,
  CARTESIAN_MISSING,
  CHART_COLOR_SLOTS,
  CHART_FAMILY,
  CHART_TYPES,
  type CalendarSpec,
  type CandlestickSpec,
  type CartesianMissing,
  type CartesianSeries,
  type CartesianSpec,
  type ChartFamily,
  type ChartSpec,
  type ChartType,
  DERIVED_KINDS,
  type DerivedKind,
  type DerivedSeries,
  type FunnelSpec,
  type FunnelStages,
  type GaugeSpec,
  type HeatmapSpec,
  type HierarchySpec,
  MAX_CHART_LEVELS,
  MAX_MOVING_WINDOW,
  METRIC_HEADLINES,
  type MapSpec,
  type MetricCardSpec,
  type MetricHeadline,
  type MetricTrend,
  type ParallelSpec,
  type PieSpec,
  REFERENCE_STATISTICS,
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
  VIEW_KINDS,
  type ViewConfig,
  type ViewConfigBase,
  type ViewKind,
  autoRunMembers,
  presentationMembers,
} from './model/config.js';
export type { CurrencyReading } from './model/currency.js';
export {
  type BoardValueSource,
  type ContentPanelKind,
  DASHBOARD_FILTER_KINDS,
  DASHBOARD_FILTER_TYPES,
  DASHBOARD_GRID_COLUMNS,
  DASHBOARD_PRESENTATION_MEMBERS,
  DASHBOARD_WIDTHS,
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
  LEGACY_GRID_COLUMNS,
  MAX_DASHBOARD_FILTERS,
  MAX_DASHBOARD_TABS,
  MAX_HEADING_LENGTH,
  MAX_MARKDOWN_LENGTH,
  MAX_PANEL_LINKS,
  type OwnedView,
  PANEL_PRESENTATION_MEMBERS,
  type PanelBinding,
  type PanelClick,
  type PanelLayout,
  type PanelPresentation,
  boardWidth,
  filterTypeOf,
  sameFilterType,
} from './model/dashboard.js';
export {
  type AggregationFieldCapability,
  type AnalysisCapability,
  type AnalysisElementCapability,
  type AnalysisLimits,
  type AnalysisSpec,
  DEFAULT_APPROXIMATE_METRICS,
  type DashboardDefinition,
  type DataViewDefinition,
  type DefinitionDescribed,
  type DefinitionNarrowing,
  type FieldAnalysisSpec,
  type FieldGroupDefinition,
  type OpenCapabilities,
  type RecordCapability,
  SYSTEM_INSTANCE_ID_PREFIX,
  SYSTEM_INSTANCE_ID_SEPARATOR,
  type SystemView,
  type ViewDefinition,
  approximateMetrics,
  isApproximate,
  isSystemInstanceId,
  parseSystemInstanceId,
  systemInstanceId,
} from './model/definition.js';
export {
  BUILTIN_FIELD_KIND_IDS,
  type BuiltinFieldKindId,
  CURRENCY_CODE_PATTERN,
  DATE_SUMMARY_FUNCTIONS,
  DEFAULT_SEARCH_MODE,
  DEFAULT_STRING_COMPARISON,
  DEFAULT_TEMPORAL,
  type DateTemporal,
  type DecimalNumeric,
  EPOCH_TIME_UNITS,
  type EpochTemporal,
  type EpochTimeUnit,
  FIELDLESS_FIELD_KIND_IDS,
  FIELD_CELL_IDS,
  FIELD_TONES,
  type FieldCellId,
  type FieldDefinition,
  type FieldKindId,
  type FieldNumeric,
  type FieldOption,
  type FieldTemporal,
  type FieldTone,
  type FixedMoneyNumeric,
  MAX_NUMERIC_SCALE,
  METADATA_FIELD_KIND_IDS,
  type MetadataFieldKindId,
  NUMERIC_TYPES,
  type NumberFormat,
  type RowMoneyNumeric,
  SEARCH_MODES,
  STRING_COMPARISONS,
  SUMMARY_FUNCTIONS,
  type SearchModeName,
  type StringComparisonName,
  type SummaryFunction,
  TEMPORAL_FIELD_KIND_IDS,
  TEMPORAL_TYPES,
  aggregationFunctionsOf,
  currencyPathOf,
  epochUnitOf,
  fieldAliasSegment,
  isDateCell,
  isFieldName,
  isFieldlessKind,
  isSingleStringField,
  numberFormatOf,
  summaryFunctionsOf,
  temporalOf,
} from './model/field.js';
export {
  FILTER_GROUP_OPERATORS,
  type FilterGroup,
  type FilterGroupOperator,
  type FilterLeaf,
  type FilterMode,
  type FilterNode,
  type FilterOperatorName,
  type FilterTree,
  type FilterValue,
  isFilterGroupOperator,
} from './model/filter.js';
export {
  CODE_REVISION,
  VIEW_AUDIENCES,
  VIEW_SCOPES,
  type ViewAudience,
  type ViewInstance,
  type ViewInstanceSummary,
  type ViewPreferences,
  type ViewScope,
  audienceOf,
  isSystemScope,
  toSummary,
} from './model/instance.js';
export type { Issue, IssuePath, IssueSeverity } from './model/issue.js';
export {
  type JsonValue,
  type LiteralEnums,
  overlaid,
  sameJson,
  without,
} from './model/json.js';
export {
  DEFAULT_RUNTIME_LIMITS,
  MAX_TIMER_DELAY_MS,
  type RuntimeLimits,
  nearestPageSize,
} from './model/limits.js';
export {
  type PagingMode,
  RECORD_PRESENTATION_MEMBERS,
  type RecordCardSpec,
  type RecordColumn,
  type RecordData,
  type RecordKey,
  type RecordLayout,
  type RecordPageTarget,
  type RecordSort,
  type RecordSummary,
  type RecordTableSpec,
  type RecordViewConfig,
  type SortDirection,
  columnHidden,
  columnPinned,
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
  say,
  text,
  textKeyOf,
  withText,
} from './model/text.js';
// filter — the filter kernel and the `FieldKind` registry.
export { type FilterCompileContext, compileFilter } from './filter/compile.js';
export {
  validateDataConfigBase,
  validateViewConfigBase,
} from './filter/configBase.js';
export {
  type FieldKindDescription,
  type FilterSummaryItem,
  type FilterSummaryRelation,
  type FilterSummaryValue,
  describeFilter,
  groupJoinWord,
  isGroupItem,
} from './filter/describe.js';
export { type FieldGroup, fieldGroups } from './filter/fieldGroups.js';
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
  isBlankLeafValue,
  isKnownEditorInput,
  issue,
  operatorsOf,
  readValue,
  withFieldKinds,
  writeValue,
} from './filter/fieldKind.js';
export { filterIndexes, isRootFilterIssue } from './filter/issuePath.js';
export {
  BUILTIN_FIELD_KINDS,
  builtinFieldKinds,
} from './filter/kinds/index.js';
export { booleanFieldKind } from './filter/kinds/boolean.js';
export {
  type DateInstant,
  dateFieldKind,
  dateTimeFieldKind,
  readInstant,
} from './filter/kinds/dateTime.js';
export {
  DELETION_STATES,
  deletionFieldKind,
  impliedDeletion,
  isDeletionState,
} from './filter/kinds/deletion.js';
export { type ArrayFilterValue, arrayFieldKind } from './filter/kinds/array.js';
export {
  elementFields,
  elementMatchFieldKind,
  variantGroups,
} from './filter/kinds/elementMatch.js';
export { enumFieldKind } from './filter/kinds/enum.js';
export { METADATA_FIELD_KINDS } from './filter/kinds/metadata.js';
export { numberFieldKind } from './filter/kinds/number.js';
export {
  PRESENCE_OPERATORS,
  compilePresence,
  describePresence,
  describePresenceParts,
  isPresenceOperator,
} from './filter/kinds/presence.js';
export { referenceFieldKind } from './filter/kinds/reference.js';
export { searchFieldKind } from './filter/kinds/search.js';
export { stringFieldKind } from './filter/kinds/string.js';
export { unmarkedErrors } from './filter/marks.js';
export { rootSearch, searchFieldOf, withRootSearch } from './filter/search.js';
export {
  type InstantRange,
  type RangeEdge,
  isValidTimeZone,
  periodOf,
  resolveDateTimeBound,
  resolveDateTimeRange,
} from './filter/time.js';
export {
  type FilterPath,
  type GroupCondition,
  type MalformedVisit,
  type TreeVisit,
  clearFilter,
  conditionOf,
  conditions,
  countLeaves,
  durationFrom,
  emptyFilter,
  filterFields,
  insertAt,
  isEmptyFilter,
  isFilterGroup,
  isFilterLeaf,
  isFilterNode,
  isNegation,
  isSimpleTree,
  mergeFilters,
  negateAt,
  nodeAt,
  removeAt,
  removeConditionAt,
  sameFilterNode,
  sameFilterTree,
  updateAt,
  walkFilter,
  walkFilterShape,
} from './filter/tree.js';
export {
  type ValidateFilterOptions,
  isBlankFilter,
  isExecutableFilter,
  validateFilter,
} from './filter/validate.js';
export {
  type AbsoluteDateTimeValue,
  type BooleanFilterValue,
  DATE_TIME_PRESETS,
  DURATION_COMPARISONS,
  type DateTimeFilterValue,
  type DateTimePreset,
  type DurationComparison,
  type DurationFilterValue,
  type EnumFilterValue,
  MAX_RELATIVE_DATE_AMOUNT,
  type NumberFilterValue,
  type NumberRange,
  type PresetDateTimeValue,
  RELATIVE_DATE_UNITS,
  type ReferenceFilterValue,
  type ReferenceItem,
  type RelativeDateDirection,
  type RelativeDateTimeValue,
  type RelativeDateUnit,
  type StringFilterValue,
  isDateTimeFilterValue,
  isDurationFilterValue,
  isFiniteNumber,
  isNonBlankString,
  isNonEmptyString,
  isNumberRange,
  isOrderedRange,
  isPlainObject,
  isReferenceFilterValue,
} from './filter/values.js';
// record — the record kernel.
export {
  FIRST_PAGE,
  compileRecord,
  compileSummaries,
  recordProjection,
  summaryAlias,
} from './record/compile.js';
export { defaultRecordConfig, recordCapabilityOf } from './record/defaults.js';
export { type DetailSection, detailSections } from './record/detail.js';
export {
  CSV_BOM,
  type CsvOptions,
  type ExportColumn,
  type ExportFormat,
  serializeCsv,
} from './record/export.js';
export {
  type CursorPaging,
  type PagedPaging,
  type RecordPaging,
  clampPage,
  cursorPaging,
  lastPageInWindow,
  pageAfterShrink,
  pageWindow,
  pagedPaging,
} from './record/paging.js';
export {
  type ColumnEdge,
  type ElementTitleView,
  type RecordCardField,
  type RecordCardView,
  type RecordColumnView,
  type RecordRow,
  type RecordView,
  type SummaryCell,
  type SummaryRow,
  type SummarySource,
  cardField,
  pageSummaries,
  projectRecord,
  projectSummaries,
  recordValue,
} from './record/project.js';
export {
  type ValidateRecordOptions,
  maxSortFields,
  validateRecord,
} from './record/validate.js';
// analysis — the analysis kernel.
export { type BucketChange, bucketChange } from './analysis/bucketChange.js';
export {
  VALUE_CANDIDATE_LIMIT,
  VALUE_CANDIDATE_OPERATORS,
  type ValueCandidate,
  type ValueCandidates,
  narrowValueCandidates,
  readValueCandidates,
  valueCandidateField,
  valueCandidateLimit,
  valueCandidateNarrowing,
  valueCandidatesConfig,
} from './analysis/candidates.js';
export {
  type AnalysisScope,
  type AnalysisScopeElement,
  analysisScope,
  elementFilterFields,
  innermostElement,
  outOfScopeNames,
  qualify,
  relativeFields,
  relativeName,
  relativeTree,
  scopePrefix,
  unknownOrOutside,
  withOutOfScope,
} from './analysis/capability.js';
export {
  type ChartData,
  type HeatmapData,
  type PieData,
  type PieSlice,
  type ScatterData,
  type ShapeContext,
  shapeChart,
} from './analysis/chart.js';
export {
  type MetricCardData,
  type MetricPeriod,
  periodRollover,
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
export { groupKeyText } from './analysis/chartRows.js';
export {
  CHART_FAMILIES,
  type ChartFamilyTraits,
  type ChartUnfit,
  type OptionsTab,
  PEAKS_ONLY_FROM,
  type SeriesMark,
  type ShapeFacts,
  chartMarks,
  familyOf,
  peaksOnlyLabels,
  seriesMark,
  valueLabelsOn,
} from './analysis/chartFamilies.js';
export {
  type Picked,
  isPercentStacked,
  isSmooth,
  isStacked,
  offersPercentStack,
  offersStacking,
  optionTabs,
  stacks,
  stageValues,
  withMovedTo,
  withPercentStack,
  withSlot,
  withSmooth,
  withStacked,
  withStageOrder,
  withStagesFrom,
} from './analysis/chartOptions.js';
export { comboAxis, comboMark, fitChartSlots } from './analysis/chartSlots.js';
export { leadMetric, switchChartType } from './analysis/chartSwitch.js';
export {
  analysisProbeLimit,
  asksForWhole,
  compileAnalysis,
  compileAnalysisTotals,
} from './analysis/compile.js';
export {
  type AnalysisLimitBounds,
  DEFAULT_MISSING_KEY,
  DEFAULT_PERCENTILE,
  type GroupFacts,
  type MetricFacts,
  type SummaryChoice,
  aliasOf,
  defaultAnalysisConfig,
  firstMetric,
  freeAlias,
  groupFacts,
  groupOfType,
  groupableFields,
  limitBounds,
  metricOfSummary,
  metricWithCondition,
  summaryChoices,
  summaryOf,
} from './analysis/defaults.js';
export {
  type BucketRange,
  type DrillContext,
  type DrilledGroup,
  bucketRange,
  drillConditions,
  drillGroups,
  focusOn,
  groupFor,
  splitBy,
  wallClockAt,
} from './analysis/drill.js';
export { drillFilter, narrowsTo } from './analysis/drillFilter.js';
export {
  CHART_PICKER_ORDER,
  type ChartFit,
  type ChartPickerGroups,
  type ChartShape,
  chartPickerGroups,
  chartUnfit,
  fitCharts,
} from './analysis/fitCharts.js';
export {
  EXPRESSION_OPERATORS,
  OPERATOR_SIGN,
  derivedMetric,
  derivedText,
  durationMetric,
  expressionText,
  formulaMetric,
  isDuration,
  isFormula,
  metricReferenceText,
  wordReferences,
} from './analysis/formula.js';
export {
  levelLabel,
  nextExpansion,
  nextLevel,
  withElements,
  withLevel,
  withoutLevelsFrom,
} from './analysis/expand.js';
export {
  rangeSpan,
  recommendDateUnit,
  resultSpan,
} from './analysis/granularity.js';
export {
  HAVING_OPERATORS,
  type HavingOperator,
  type HavingRow,
  havingRows,
  withHavingRows,
} from './analysis/having.js';
export {
  type MetricCondition,
  metricCondition,
} from './analysis/metricCondition.js';
export {
  type MetricFunction,
  formulaFormat,
  metricFieldOf,
  metricFormat,
  metricFunctionOf,
  metricMeasure,
  metricMeasures,
  momentMetrics,
  readsAsItsField,
} from './analysis/metricFormat.js';
export {
  type AnalysisColumnView,
  type AnalysisView,
  type ColumnCurrency,
  measureColumns,
  momentColumns,
  projectAnalysis,
  rowCurrency,
} from './analysis/project.js';
export {
  type ValidateAnalysisOptions,
  validateAnalysis,
} from './analysis/validate.js';
export {
  isAdditiveMetric,
  isChartColor,
  validateChart,
} from './analysis/validateChart.js';
// dashboard — the dashboard kernel.
export {
  type BoardClick,
  type PressableGroup,
  boardFilterChoices,
  boardValueChoices,
  crossFilterChoices,
  fillUrl,
  pressableGroups,
  setPanelClick,
  takesGroup,
  urlPlaceholders,
  validateBoardClick,
  validatePanelClick,
} from './dashboard/click.js';
export {
  CLICK_GO_KINDS,
  type ClickChoice,
  type ClickDraft,
  type ClickGoKind,
  type MappingSources,
  boardValueSources,
  clickDraftGaps,
  clickDraftOf,
  draftedClick,
  mappedValues,
  urlFillable,
  valueSourceKey,
  withBoard,
} from './dashboard/clickDraft.js';
export { emptyDashboardConfig } from './dashboard/defaults.js';
export {
  type NewContentPanel,
  type NewPanel,
  type NewPanelPlacement,
  type NewViewPanel,
  addPanel,
  compactTab,
  defaultPanelSize,
  duplicatePanel,
  editContent,
  movePanelToTab,
  referToSaved,
  removePanel,
  renamePanel,
  replacePanelView,
  setBoardWidth,
  setPresentation,
} from './dashboard/edit.js';
export {
  type NewFilter,
  addFilter,
  moveFilter,
  removeFilter,
  removeFixedScope,
  renameFilter,
  retypeFilter,
  setFilterDefault,
  setFilterMultiple,
  setFilterOneDay,
  setFilterOptions,
  setFilterRequired,
  setTimeGrouping,
} from './dashboard/filterEdit.js';
export {
  FILTER_TYPE_OPERATOR,
  ONE_DAY_PRESETS,
  admitFilters,
  defaultFilters,
  filterCondition,
  filterControlValue,
  filterEditor,
  filterOperatorOf,
  filterStoredValue,
  filterValueIssues,
  filtersOf,
  isBlankFilterValue,
  isOneDayValue,
  panelFilterTree,
} from './dashboard/filters.js';
export {
  type ArrangeStep,
  type OrderStep,
  type PlacedPanel,
  STACKED_COLUMNS,
  arrangePanel,
  bottomOf,
  compactLayout,
  fitsGrid,
  freeSpot,
  grownLayout,
  overlaps,
  placePanel,
  placePanelIn,
  readingOrder,
  reorderPanel,
  reorderPanelIn,
  stackedLayout,
  withLayouts,
} from './dashboard/layout.js';
export {
  boardCondition,
  mapGlobalFilter,
  mergeGlobalFilter,
} from './dashboard/merge.js';
export { migrateDashboardConfig } from './dashboard/migrate.js';
export {
  bindingsOf,
  clickOf,
  clicksFilter,
  freshId,
  isContentPanel,
  isOwnedPanel,
  isPresentationMember,
  isSafeContentUrl,
  isViewPanel,
  panelTab,
  panelsOf,
  presentationMembersOf,
  referencedInstance,
  tabsOf,
} from './dashboard/panels.js';
export {
  addTab,
  moveTab,
  removeTab,
  renameTab,
  validateTabs,
} from './dashboard/tabs.js';
export {
  type PanelDefinition,
  type PanelReference,
  type PanelReferences,
  type ValidateDashboardOptions,
  coversScope,
  validateDashboard,
  validateLayout,
} from './dashboard/validate.js';
export {
  validateFilterFields,
  validateTimeGrouping,
} from './dashboard/validateFilters.js';
export {
  type DataPanelSource,
  type FilterReach,
  type PanelFields,
  autoBindings,
  bindPanel,
  filterReach,
  filtersOnTab,
  unbindPanels,
  wireableFields,
  wiredOptions,
} from './dashboard/wiring.js';
// runtime — the engine, the runtime contracts and the types their
// signatures name; never a part the runtime is built from.
export {
  type CreateInput,
  ViewEngine,
  type ViewEngineOptions,
  type ViewResource,
  type ViewListing,
} from './runtime/viewEngine.js';
export type { ConflictChoice, WriteTarget } from './runtime/writeLedger.js';
export {
  type ValidateDefinitionOptions,
  validateDefinition,
} from './runtime/validateDefinition.js';
export {
  type DefineViewOptions,
  defineView,
} from './runtime/define/defineView.js';
export type { DefineViewSpec, FieldSpec } from './runtime/define/spec.js';
export {
  type AnyViewRuntime,
  type DefinitionFor,
  type OpenOptions,
  type QueryStatus,
  type RecordViewRuntime,
  type RuntimeFor,
  type ViewQueryState,
  type ViewResult,
  type ViewRuntime,
  type ViewRuntimeState,
  hasAsked,
  hasResult,
} from './runtime/viewRuntimeTypes.js';
export { isRecordRuntime } from './runtime/recordRuntime.js';
export {
  ExportCancelled,
  type ExportRowsOptions,
  type ExportedRows,
  isExportCancelled,
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
  ALWAYS_VISIBLE,
  type RuntimeEnvironment,
  type ViewErrorContext,
  type ViewErrorEvent,
  type ViewErrorKind,
  type VisibilitySource,
  defaultRuntimeEnvironment,
} from './runtime/environment.js';
export type {
  OptionSource,
  ProjectedAnalysis,
  ProjectedBase,
  ProjectedRecord,
  ProjectedView,
  ViewSource,
} from './runtime/source.js';
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
  type ViewPermissions,
  type ViewStore,
  type WriteContext,
  emptyPreferences,
} from './store/ViewStore.js';
