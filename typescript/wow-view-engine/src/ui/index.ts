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
// The one callout recipe — the registry's `Alert`, one line high — so a
// host drawing its own notice above a view can wear the same face.
export * from './alerts.js';
export * from './AnalysisChart.js';
export * from './AnalysisTable.js';
export * from './AppliedBar.js';
export * from './BulkStatus.js';
export * from './ColumnSettings.js';
export * from './DashboardArrange.js';
export * from './DashboardGrid.js';
export * from './DashboardPanel.js';
export * from './DashboardPanels.js';
export * from './dashboard/extensions.js';
export * from './DashboardWorkbench.js';
export * from './describeConfig.js';
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
export * from './download.js';
export * from './EditorBand.js';
export * from './EmbeddedView.js';
export * from './ExportDialog.js';
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
export * from './DataWorkbench.js';
export * from './features.js';
export * from './RenderBoundary.js';
export * from './RefreshControl.js';
export * from './ResultToolbar.js';
export * from './RowActions.js';
export * from './SaveActions.js';
export * from './SaveAsDialog.js';
export * from './SortSettings.js';
export * from './StatusStrip.js';
// The hook ships beside the toggle: `EmbeddedView` has no title bar to put a
// button in, so a host that wants its embed to fill the screen owns the
// control and points this at the surface it got a ref to.
export * from './ViewExpansion.js';
export * from './ViewHeader.js';
export * from './ViewList.js';
export * from './ViewSwitcher.js';
export * from './ViewManager.js';
export * from './ViewSurface.js';
export * from './WorkbenchShell.js';
export * from './workbench/parts.js';
export * from './workbench/RecordParts.js';
export * from './workbench/AnalysisParts.js';
export * from './workbench/NewView.js';
export * from './WriteOutcome.js';
