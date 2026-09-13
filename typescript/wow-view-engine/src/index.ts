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

export type { FilterField, FilterOption } from './filter/filterTypes.js';
export type { DeepReadonly } from './lib/types.js';

export type * from './filter/filterModel.js';
export {
  newFilterNode,
  createFilterConfiguration,
  validateFilterConfiguration,
  compileFilterConfiguration,
  compileBuiltinFilter,
  clearBuiltinFilterProps,
  clearFilterValues,
  getFieldOperators,
  isSimpleFilter,
  FILTER_OPERATORS,
} from './filter/filterCore.js';

export type * from './contracts/viewModel.js';
export type * from './contracts/ViewHost.js';
export { ViewEngine } from './engine/ViewEngine.js';
export type {
  ViewPosition,
  DataViewPosition,
  RecordViewPosition,
  AnalysisViewPosition,
  DashboardViewPosition,
  ViewPositionOptions,
} from './engine/ViewEngine.js';
export { getRecordSummaryMetrics } from './record/recordPresentation.js';
export {
  RECORD_SUMMARY_LABELS,
  getRecordSummaryFunctions,
} from './record/recordPresentation.js';
export { formatRecordNumber } from './record/recordValueFormat.js';
export {
  getRecordColumnPinning,
  orderRecordColumns,
} from './record/recordColumns.js';
export {
  calculateRecordSummary,
  createRecordSummaryQuery,
  readRecordSummaryResult,
} from './record/recordSummary.js';
export { validateViewDefinition } from './contracts/validation/definitionValidation.js';
export { validateViewInstance } from './contracts/validation/instanceValidation.js';
export {
  readRecordValue,
  getRecordKey,
  validateRecordRows,
} from './record/recordValidation.js';
export {
  RECORD_COLUMN_MIN_WIDTH,
  RECORD_COLUMN_MAX_WIDTH,
  RECORD_COLUMN_DEFAULT_WIDTH,
} from './record/recordColumns.js';
export { sameFilterQuery } from './filter/filterTree.js';

export {
  MemoryViewHost,
  type MemoryViewHostOptions,
} from './record/MemoryViewHost.js';

export { ViewServiceError } from './contracts/viewServiceContract.js';
export type {
  ViewServiceErrorCode,
  ViewCreateContext,
  ViewDeleteResult,
  ViewPermissionSnapshot,
} from './contracts/viewServiceContract.js';

export type {
  FilterOptionSource,
  FilterOptionValue,
  FilterOptionItem,
} from './filter/filterOptionSource.js';

export { resolveRecordPresentation } from './record/resolveRecordPresentation.js';

export type * from './analysis/analysisModel.js';
export { MAX_ANALYSIS_ELEMENTS } from './analysis/analysisModel.js';
export { compileAnalysis } from './analysis/analysisCompiler.js';
export {
  validateAnalysisResult,
  analysisRowKey,
} from './analysis/analysisResult.js';

export {
  analysisScopeContext,
  compileAnalysisExpression,
} from './analysis/analysisCompiler.js';
export { adaptWowAnalysisSchema } from './analysis/wowAnalysis.js';
export type {
  WowAnalysisSchema,
  WowAnalysisSchemaOptions,
} from './analysis/wowAnalysis.js';
export type { AnalysisPresentation } from './analysis/analysisPresentation.js';
export {
  projectAnalysis,
  validateAnalysisPresentation,
} from './analysis/analysisProjection.js';

export { formatAnalysisValue } from './analysis/analysisFormatting.js';

export { ANALYSIS_VISUALIZATIONS } from './analysis/analysisVisualizations.js';
export type { AnalysisVisualizationType } from './analysis/analysisVisualizations.js';

export type * from './dashboard/dashboardModel.js';
export { dashboardEditorKey } from './dashboard/dashboardEditorKey.js';
export { validateDashboardConfig } from './dashboard/dashboardValidation.js';

export {
  LEGACY_VIEW_FORMATS,
  projectSupportedInstance,
  requireSupportedInstance,
} from './contracts/viewServiceContract.js';
export type { SupportedViewFormats } from './contracts/viewServiceContract.js';

export {
  DashboardRuntime,
  type DashboardSnapshot,
  type DashboardPanelSnapshot,
} from './dashboard/DashboardRuntime.js';
export {
  compileDashboardScope,
  validateDashboardExpression,
} from './dashboard/dashboardFilters.js';
export type {
  DashboardHost,
  DashboardCandidate,
} from './contracts/ViewHost.js';
