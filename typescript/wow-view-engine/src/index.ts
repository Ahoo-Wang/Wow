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

export type * from './record/recordModel.js';
export type * from './record/ViewHost.js';
export { ViewEngine } from './record/ViewEngine.js';
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
export {
  validateViewDefinition,
  validateViewInstance,
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
  LocalStorageViewHost,
  type LocalStorageViewHostOptions,
} from './record/LocalStorageViewHost.js';

export { ViewServiceError } from './record/viewServiceContract.js';
export type {
  ViewServiceErrorCode,
  ViewCreateContext,
  ViewPermissionSnapshot,
  ViewStorageLock,
} from './record/viewServiceContract.js';

export type {
  FilterOptionSource,
  FilterOptionValue,
  FilterOptionItem,
} from './filter/filterOptionSource.js';

export { resolveRecordPresentation } from './record/resolveRecordPresentation.js';
