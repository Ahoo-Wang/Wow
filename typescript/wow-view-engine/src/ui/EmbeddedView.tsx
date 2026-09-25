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

import type { ReactNode } from 'react';
import type { DataViewConfig, FilterTree, ViewKind } from '../model/index.js';
import type { RecordRow } from '../record/index.js';
import {
  isRecordRuntime,
  type AnyViewRuntime,
  type ViewRuntime,
} from '../runtime/index.js';
import { resultIssues } from '../runtime/source.js';
import {
  useAnalysisEditor,
  useOpenView,
  useViewRuntime,
} from '../react/index.js';
import { analysisIssueNamer } from './analysis/issueNames.js';
import { EmbedFrame } from './embed/EmbedFrame.js';
import { EmbedExpand, EmbedHead, OpenInWorkbench } from './embed/EmbedHead.js';
import { EmbeddedAnalysis } from './embed/EmbeddedAnalysis.js';
import { EmbeddedRecord } from './embed/EmbeddedRecord.js';
import type { EmbedBaseProps, EmbedInteraction } from './embed/options.js';
import { useViewMessages } from './MessagesProvider.js';
import { ErrorStrip, WarningStrip } from './StatusStrip.js';

export type {
  EmbedBaseProps,
  EmbedInteraction,
  EmbedSize,
} from './embed/options.js';

export interface EmbeddedViewProps extends EmbedBaseProps {
  /**
   * An outer condition ANDed onto the view's own, in the view's field names:
   * the page's narrowing, locked — the reader sees it in the applied band
   * and cannot take it off. It is admitted like a user's own filter, so a
   * host cannot widen a view past what its definition allows, and it never
   * reaches the saved config. It is not a security boundary: see the
   * README's embedding section.
   */
  scopeFilter?: FilterTree | null;
  /** How far the reader may go (`EmbedInteraction`); `static` by default. */
  interaction?: EmbedInteraction;
  /**
   * The view's search box, at the end of the applied band, where its
   * definition declares a search field (off by default). Record views only,
   * and the interactive tier only: the tier is the ceiling and the switch
   * opts in within it (D36).
   */
  withSearch?: boolean;
  /**
   * The export button and window (D14), in the embed's first row (off by
   * default). Record views only, and the interactive tier only (D36,
   * amending D24 Q24): with it, rows can be picked, since the window offers
   * to take the picked ones.
   */
  withExport?: boolean;
  /**
   * What the host offers on one row of a record view. The slot takes the row
   * alone, because there is no view to command here.
   */
  rowActions?(row: RecordRow): ReactNode;
}

/** The kinds this entry draws; a dashboard is `EmbeddedDashboard`'s. */
const DATA_KINDS: readonly ViewKind[] = ['record', 'analysis'];

/**
 * One saved record or analysis view inside a business page: the result, and
 * what the host switched on around it (D22).
 *
 * The workbench exists to let a user *change* how they observe; this exists
 * to let a page *show* what someone already decided. So there is no view
 * list, no condition editor and no save — nothing it does is written
 * anywhere (D36) — an order page embedding "recent shipments for this
 * customer" wants the rows, not a second application. How far a reader may
 * go is one explicit tier (`interaction`); the rest are switches: the
 * title, the search, the export, auto-refresh, filling the screen, 在工作台
 * 中打开. A dashboard is `EmbeddedDashboard`, split by resource as the
 * workbenches are; this one names it as a view it cannot show.
 *
 * Everything it drops is chrome. Admission, paging, auto-refresh and the
 * request budget are the runtime's, identical to the workbench's, because
 * both are compositions over the same controllers.
 */
export function EmbeddedView(props: EmbeddedViewProps) {
  const { engine, instanceId, scopeFilter = null } = props;
  // The condition goes in with the config, not after it: `useOpenView` hands
  // it to `engine.open`, so the opening query is already scoped rather than
  // going out wide and being narrowed a moment later. One the definition
  // refuses is reported as a refusal instead of being quietly dropped.
  const opened = useOpenView(engine, instanceId, scopeFilter);
  return (
    <EmbedFrame
      engine={engine}
      opened={opened}
      kinds={DATA_KINDS}
      props={props}
    >
      {runtime => <EmbeddedData runtime={runtime} props={props} />}
    </EmbedFrame>
  );
}

/**
 * What the view says about itself, then the kind's own body. A component
 * rather than a branch above because each kind's controller is a hook, and
 * a hook cannot be called conditionally.
 */
function EmbeddedData({
  runtime,
  props,
}: {
  runtime: AnyViewRuntime;
  props: EmbeddedViewProps;
}) {
  const {
    instanceId,
    interaction = 'static',
    withTitle = false,
    headingLevel = 2,
    withSearch = false,
    withExport = false,
    openInWorkbench = true,
    expandable = false,
    onNavigate,
    rowActions,
  } = props;
  const data = runtime as ViewRuntime<DataViewConfig>;
  const state = useViewRuntime(data);
  const messages = useViewMessages();
  const interactive = interaction === 'interactive';
  // A finding names its dimensions, metrics and fields as the screen does —
  // columns as they are headed (`analysisIssueNamer`); over a record view
  // the editor is empty and names nothing.
  const nameIssue = analysisIssueNamer(useAnalysisEditor(data), messages);

  // A config the definition no longer admits opens but never executes, so
  // without this a record sits at an empty frame and an analysis at a
  // skeleton that never resolves. The workbenches say so; so does this.
  const issues = (state?.issues ?? []).map(nameIssue);
  const errors = issues.filter(found => found.severity === 'error');
  // A warning blocks nothing, so the result still shows, with the warning
  // above it: an embed hides the editor, and this is the one place a reader
  // learns the view is not quite what its author saved. What the result
  // says about itself is said here too, and for a stronger reason than in a
  // workbench: there is nothing else on screen to correct a page total that
  // wears the word "total", or a pie drawn from a truncated grouping.
  const warnings = [...issues, ...resultIssues(state?.result?.data)];

  const title = withTitle ? state?.title : undefined;
  // 在工作台中打开: the saved view under the page's narrowing, in force
  // there as here — locked, nobody's to take off (D26 Q30) — interactive
  // only, and only with a route to go by.
  const open = interactive && openInWorkbench && onNavigate && (
    <OpenInWorkbench
      to={{
        kind: 'view',
        definitionId: data.definition.id,
        instanceId,
        scopeFilter: data.scopeFilter,
        filter: null,
      }}
      onNavigate={onNavigate}
    />
  );
  // 「铺满屏幕」 last on the row, as it is the last of a workbench's view
  // controls: interactive only, where the host asked for it.
  const expand = interactive && expandable && <EmbedExpand />;
  const head = (actions: ReactNode) => (
    <EmbedHead title={title} headingLevel={headingLevel}>
      {open || actions || expand ? (
        <>
          {open}
          {actions}
          {expand}
        </>
      ) : null}
    </EmbedHead>
  );

  // An error takes the result's place; it does not take the warnings' — a
  // config can carry both. There is no condition editor here, so no finding
  // is marked anywhere else.
  if (errors.length > 0)
    return (
      <>
        {head(null)}
        <ErrorStrip issues={errors} />
        <WarningStrip issues={warnings} />
      </>
    );
  const notices = <WarningStrip issues={warnings} />;
  return isRecordRuntime(data) ? (
    <EmbeddedRecord
      runtime={data}
      interactive={interactive}
      withSearch={withSearch}
      withExport={withExport}
      rowActions={rowActions}
      head={head}
      notices={notices}
    />
  ) : (
    <EmbeddedAnalysis
      runtime={data}
      interactive={interactive}
      onNavigate={onNavigate}
      head={head}
      notices={notices}
    />
  );
}
