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
 * The `/react` entry: hooks and headless controllers over the runtime.
 *
 * Nothing here renders. A controller returns read-only state and action
 * functions, never JSX, class names or vendor types, so the default UI and a
 * hand-built one consume exactly the same contract.
 */
export type {
  RecordActionSlots,
  RecordBulkActionContext,
  RecordDetailPlacement,
  RecordDetailSection,
  RecordDetailSectionContext,
  RecordGlobalActionContext,
  RecordRowActionContext,
} from './actions.js';
export {
  browserRuntimeEnvironment,
  documentVisibility,
} from './environment.js';
export { kindMismatch } from './issues.js';
export { toIssue } from '../runtime/issues.js';
export {
  type AnalysisEditorController,
  useAnalysisEditor,
} from './useAnalysisEditor.js';
export type { AnalysisFieldOption } from './analysisFields.js';
export type { DropCause } from './analysisEditing.js';
export type { DropNotice, MetricRemoval } from './useReshape.js';
export {
  type AnalysisResultController,
  type FollowUp,
  type FollowUpAction,
  type FollowUpGroup,
  type SplitOption,
  useAnalysisResult,
} from './useAnalysisResult.js';
export {
  type RefreshController,
  useAutoRefresh,
  useRefreshCountdown,
} from './useAutoRefresh.js';
export {
  type BulkCommand,
  type BulkCommandOptions,
  type BulkFailure,
  type BulkOutcome,
  type BulkProgress,
  type BulkRun,
  type BulkSelection,
  failureReasons,
  useBulkCommand,
} from './useBulkCommand.js';
export { type SearchBoxController, useSearchBox } from './useSearchBox.js';
export {
  type UnavailableController,
  useUnavailable,
} from './useUnavailable.js';
export {
  type DashboardController,
  type DashboardPanelView,
  useDashboard,
} from './useDashboard.js';
export {
  type FollowUpHost,
  ownedNavigation,
  usePanelFollowUps,
} from './usePanelFollowUps.js';
export {
  type FilterEditorController,
  type FilterTreeController,
  type TreeControllerInput,
  treeController,
  useFilterEditor,
} from './useFilterEditor.js';
export {
  type RecordDetailControl,
  type RecordDetailController,
  useRecordDetail,
} from './useRecordDetail.js';
export {
  type RecordExportController,
  type RecordExportOptions,
  type RecordExportOutcome,
  type RecordExportProgress,
  type RecordExportScope,
  type RecordExportScopes,
  useRecordExport,
} from './useRecordExport.js';
export {
  type RecordTableController,
  type ToggleSelectionOptions,
  type ToggleSortOptions,
  useRecordTable,
} from './useRecordTable.js';
export {
  type SaveAbilities,
  type SaveCommandState,
  type SaveCommands,
  type SaveTargetInput,
  useSaveCommands,
} from './useSaveCommands.js';
export {
  VALUE_CANDIDATE_DEBOUNCE_MS,
  type ValueCandidatesController,
  useValueCandidates,
} from './useValueCandidates.js';
export {
  type DashboardOpening,
  type OpenViewState,
  type SnapshotOf,
  type ViewRuntimeStore,
  useOpenView,
  useViewEngine,
  useViewRuntime,
} from './useViewEngine.js';
export {
  type ViewListOptions,
  type ViewListReloadOptions,
  type ViewListState,
  useViewList,
} from './useViewList.js';
export {
  type ViewManagerController,
  useViewManager,
} from './useViewManager.js';
export { PREFERENCES_KEY } from './manager/outcomes.js';
export type {
  ManagedInstanceAbilities,
  ViewManagerAbilities,
} from './manager/abilities.js';
export {
  type DrillTarget,
  type HeldView,
  type ViewOrigin,
  type WorkbenchController,
  type WorkbenchOptions,
  useWorkbench,
} from './useWorkbench.js';
// The write-outcome vocabulary, on the entry rather than behind it: a surface
// that draws the save commands has to know which outcomes the engine is still
// answering for, and `/ui` is only the first such host. Left unexported, every
// one of them derives the rule again — which is what this module exists to
// stop.
export {
  type RecoveredWrite,
  type SettledWrite,
  UNRECOVERED,
  UNSENT,
  blocksNewIntent,
  holdsHandle,
  mayRefuse,
  mayReplace,
  recovered,
  refused,
  savesView,
  settle,
  strandedHandle,
  unsettled,
} from './writes.js';
export {
  type InstanceSyncOptions,
  useInstanceSync,
} from './workbench/instanceSync.js';
export {
  type LeaveGuard,
  type LeaveGuardOptions,
  type LeaveGuardState,
  useLeaveGuard,
} from './workbench/leaveGuard.js';
export {
  type Blank,
  type NewViewOptions,
  blankView,
} from './workbench/newView.js';
export { useReleaseDeleted } from './workbench/releaseDeleted.js';
export {
  useHandOver,
  useHandedConditions,
  useHandedRemoval,
} from './workbench/handOver.js';
