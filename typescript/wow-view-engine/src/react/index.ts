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
export * from './actions.js';
export * from './environment.js';
export * from './issues.js';
export * from './useAnalysisEditor.js';
export * from './useAnalysisResult.js';
export * from './useAutoRefresh.js';
export * from './useBulkCommand.js';
export * from './useDashboard.js';
export * from './useFilterEditor.js';
export * from './useRecordExport.js';
export * from './useRecordTable.js';
export * from './useSaveCommands.js';
export * from './useViewEngine.js';
export * from './useViewList.js';
export * from './useViewManager.js';
export * from './useWorkbench.js';
// The write-outcome vocabulary, on the entry rather than behind it: a surface
// that draws the save commands has to know which outcomes the engine is still
// answering for, and `/ui` is only the first such host. Left unexported, every
// one of them derives the rule again — which is what this module exists to
// stop.
export * from './writes.js';
export * from './workbench/instanceSync.js';
export * from './workbench/leaveGuard.js';
export * from './workbench/newView.js';
export * from './workbench/releaseDeleted.js';
