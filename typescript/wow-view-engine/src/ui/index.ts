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
 * primitives. Every component here consumes a controller from `/react` and
 * nothing else, so replacing one is a matter of writing different markup
 * against the same contract.
 *
 * Styles ship separately as `@ahoo-wang/fetcher-view-engine/styles.css`.
 */
export * from './AnalysisChart.js';
export * from './AnalysisEditor.js';
export * from './AnalysisTable.js';
export * from './AnalysisWorkbench.js';
export * from './AppliedBar.js';
export * from './DashboardGrid.js';
export * from './DashboardPanels.js';
export * from './DashboardWorkbench.js';
export * from './describeConfig.js';
export * from './EditorBand.js';
export * from './EmbeddedView.js';
export * from './FilterPanel.js';
export * from './messages.js';
// The catalogues ship beside the formatters so a host can compose one:
// `{ ...zhCN, 'label.filter.apply': '确定' }`. `zh-CN` is a leaf module no
// component imports, so a bundle that only pulls in components drops it.
export * from './messages/en.js';
export * from './messages/zh-CN.js';
export * from './MessagesProvider.js';
export * from './FilterValueEditor.js';
export * from './kinds.js';
export * from './LeaveGuard.js';
export * from './RecordCards.js';
export * from './RecordPagination.js';
export * from './RecordTable.js';
export * from './RecordWorkbench.js';
export * from './ResultToolbar.js';
export * from './RowActions.js';
export * from './SaveActions.js';
export * from './SaveAsDialog.js';
export * from './StatusStrip.js';
export * from './ViewHeader.js';
export * from './ViewList.js';
export * from './ViewManager.js';
export * from './ViewSurface.js';
export * from './WorkbenchShell.js';
export * from './WriteOutcome.js';
