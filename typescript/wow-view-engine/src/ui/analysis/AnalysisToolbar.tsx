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

import type { RefObject } from 'react';
import { Settings2Icon, TableIcon } from 'lucide-react';
import { havingRows, type AnalysisColumnView } from '../../analysis/index.js';
import type { AnalysisHavingExpression } from '../../model/index.js';
import type { AnalysisEditorController } from '../../react/index.js';
import type { ViewRuntime } from '../../runtime/index.js';
import { Button } from '../components/button.js';
import { ToggleGroup, ToggleGroupItem } from '../components/toggle-group.js';
import { columnTitle, valueText } from '../display.js';
import { ExportButton } from '../ExportDialog.js';
import { IconTooltip } from '../IconButton.js';
import { SPACE, TEXT_UI } from '../layout.js';
import {
  useViewMessages,
  type MessageFormatters,
} from '../MessagesProvider.js';
import { Toolbar } from '../toolbar.js';
import { useSurfaceDisplay } from '../ViewSurface.js';
import { CHART_ICON, glyphType } from './chartIcons.js';
import { useAnalysisExportOffer } from './exportOffer.js';

export interface AnalysisToolbarProps {
  analysis: AnalysisEditorController;
  /**
   * The columns the reading names: the result's, or — while the first
   * answer is on its way, or after it failed — the question's
   * (`useAnalysisResult().columns`). The bar is the same bar either way,
   * so it stands where it will stand from the moment a query is sent.
   */
  columns: readonly AnalysisColumnView[];
  /**
   * Whether the visualization panel is open, and the press that opens or
   * closes it. Left out where the host switched the panel off
   * (`WorkbenchFeatures.visualization`): the button is then not on the bar
   * at all, as every feature turned off is absent rather than disabled (D4).
   */
  visualizing?: boolean;
  onVisualize?(open: boolean): void;
  /**
   * The press that opened the panel, so the panel can hand the keyboard
   * back to it when it closes (A1). Held by `useVisualizationFocus` for the
   * host that owns the level the panel is at.
   */
  visualizeRef?: RefObject<HTMLButtonElement | null>;
  disabled?: boolean;
  /**
   * The view whose result 「导出」 takes away, and the title its file is
   * named after (D25 Q28). Left out — the host switched exports off
   * (`WorkbenchFeatures.export`), an embed — there is no export button; with
   * it, the button is there while groups are on screen to take.
   */
  exporting?: { runtime: ViewRuntime; title: string };
}

/**
 * The first row of the analysis result (D12 Ⅳ): on the left, what the
 * numbers below are — the dimensions, the metrics and which groups were
 * kept, in one line, as the result was actually shaped; on the right, how
 * they are looked at. Looking is the result's business, not the question's,
 * so the layout switch and the way into the visualization panel live here
 * rather than in the tray (D20). Table or chart redraws the same rows.
 */
export function AnalysisToolbar({
  analysis,
  columns,
  visualizing,
  onVisualize,
  visualizeRef,
  disabled,
  exporting,
}: AnalysisToolbarProps) {
  const messages = useViewMessages();
  // The record toolbar's export, over the groups (`useAnalysisExportOffer`).
  const offer = useAnalysisExportOffer({
    runtime: exporting?.runtime ?? null,
    title: exporting?.title ?? '',
  });
  const { locale } = useSurfaceDisplay();
  const reading = withKept(
    analysisReading(columns, messages),
    analysis.ranHaving,
    columns,
    messages,
    locale,
  );
  const ChartIcon = CHART_ICON[glyphType(analysis.chart.type)];
  return (
    <Toolbar
      data-slot="result-toolbar"
      aria-label={messages.label('label.toolbar.title')}
      className={`flex flex-wrap items-center ${SPACE.GROUPS}`}
    >
      {/* Wraps rather than truncates: the having comes last, and it is
          the part that explains groups missing from the table — a line cut
          at its tail would cut exactly that. */}
      <span
        data-slot="analysis-reading"
        className={`text-muted-foreground min-w-0 ${TEXT_UI}`}
      >
        {reading}
      </span>
      <div className={`ml-auto flex flex-wrap items-center ${SPACE.GROUPS}`}>
        <ToggleGroup
          value={[analysis.layout]}
          onValueChange={value => {
            const next = value[0];
            if (next === 'table' || next === 'chart') analysis.setLayout(next);
          }}
          variant="outline"
          size="sm"
          spacing={0}
          aria-label={messages.label('label.analysis.layout')}
        >
          {/* Icons, named for a reader and on hover (`IconTooltip`), as the
              record view's layout switch is: which segment is pressed says
              which layout is on, so the word adds nothing a glance does not
              have, and the bar keeps its width for the reading on the left.
              The chart segment draws the type the chart layout will draw —
              the visualization panel's glyph for it (`CHART_ICON`) — so the
              switch says what pressing it gets, as Metabase's does; its name
              stays 「图表」, the layout, which is what the press changes. */}
          <IconTooltip
            label={messages.label('label.layout.table')}
            render={<ToggleGroupItem value="table" />}
          >
            <TableIcon />
          </IconTooltip>
          <IconTooltip
            label={messages.label('label.layout.chart')}
            render={<ToggleGroupItem value="chart" />}
          >
            <ChartIcon />
          </IconTooltip>
        </ToggleGroup>
        {onVisualize && (
          <Button
            ref={visualizeRef}
            variant="outline"
            size="sm"
            aria-pressed={visualizing === true}
            data-slot="visualize"
            disabled={disabled}
            onClick={() => onVisualize(visualizing !== true)}
          >
            {/* The panel's options glyph, not a chart: the chart segment
                beside it already draws the chart, and two identical bars
                side by side read as one control twice. */}
            <Settings2Icon data-icon="inline-start" />
            {messages.label('label.analysis.visualize')}
          </Button>
        )}
        {/* No totals switch here. It was a checkbox that only the table
            layout drew, so switching 表格／图表 moved everything beside it
            (the user's 2026-09-23 review); the totals row is a setting of
            the table, and it is set where the table's other settings are —
            the visualization panel's table options (`ChartOptions`). */}
        {/* Last, at the right end, as on the record view's toolbar: the same
            bordered icon button opening the same window (D14, D25 Q28). The
            file is the table's reading whichever layout is showing. */}
        {offer && <ExportButton {...offer} />}
      </div>
    </Toolbar>
  );
}

/**
 * What a result's numbers are, in one line: 「按仓库 · 记录数」 — the
 * dimensions, then the metrics, each as its column is headed: a time
 * dimension with its granularity, 「按创建时间（按日） · 记录数」, since the
 * axis and the header under it say 「创建时间（按日）」 and a line that
 * dropped it read as a different question. The toolbar
 * says it over the rows; a new analysis in a dashboard is named by it until
 * its author names it (D22 C).
 */
export function analysisReading(
  columns: readonly AnalysisColumnView[],
  messages: MessageFormatters,
): string {
  // The separator is the catalogue's, as it is wherever this package lists
  // names in a sentence (`charts/reading.ts`): 「、」 in Chinese, ", " in
  // English.
  const join = messages.label('label.filter.join');
  const dimensions = columns
    .filter(column => column.role === 'group')
    .map(column => columnTitle(column, messages))
    .join(join);
  const metrics = columns
    .filter(column => column.role === 'metric')
    .map(column => columnTitle(column, messages))
    .join(join);
  return dimensions === ''
    ? messages.label('label.analysis.reading-flat', { metrics })
    : messages.label('label.analysis.reading', { dimensions, metrics });
}

/**
 * The reading with the 「只保留」 that ran said after the metrics (the
 * 2026-09-23 audit, P0-2). A saved view opens with its tray folded, so the
 * having was nowhere on screen: the table drew two warehouses of four while
 * the totals row counted every record, and a reader took the other two for
 * warehouses without data. Metabase says a post-aggregation filter in the
 * question's header for the same reason.
 *
 * Each row is said as the tray states it — the metric as its column is
 * headed (`columnTitle`, which `metricReference` composes too), the
 * comparison in the tray's own word, rows joined by its 「并且」 — and the
 * value as the column prints its numbers, so 「大于 ¥2,000.00」 is compared
 * with the cells it is about. A having the rows cannot say (a range, a set,
 * an OR: `havingRows`) is still said to be there, in words that do not
 * pretend to know which groups it kept.
 */
function withKept(
  reading: string,
  having: AnalysisHavingExpression | undefined,
  columns: readonly AnalysisColumnView[],
  messages: MessageFormatters,
  locale: string | undefined,
): string {
  if (having === undefined) return reading;
  const rows = havingRows(having);
  if (rows === null || rows.length === 0)
    return messages.label('label.analysis.reading-kept-custom', { reading });
  const and = ` ${messages.label('label.analysis.having-and')} `;
  const conditions = rows
    .map(row => {
      const column = columns.find(entry => entry.alias === row.metric);
      return messages.label('label.analysis.reading-kept-row', {
        metric: column ? columnTitle(column, messages) : row.metric,
        operator: messages.label(`label.having.op.${row.operator}`),
        value: valueText(row.value, messages, column?.numberFormat, locale),
      });
    })
    .join(and);
  return messages.label('label.analysis.reading-kept', {
    reading,
    conditions,
  });
}
