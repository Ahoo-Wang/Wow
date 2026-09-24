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

import { useMemo, useState, type RefObject } from 'react';
import { ArrowLeftIcon } from 'lucide-react';
import {
  isAdditiveMetric,
  measureColumns,
  momentColumns,
  optionTabs,
  type AnalysisColumnView,
  type OptionsTab,
  type Picked,
} from '../../analysis/index.js';
import type {
  AnalysisGroup,
  AnalysisMetric,
  ChartSpec,
  RecordData,
} from '../../model/index.js';
import { cn } from 'cn';
import {
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
} from '../components/tabs.js';
import { useColumnTitle, useValueLabel } from '../charts/family.js';
import { IconButton } from '../IconButton.js';
import { LANDING_HEADING, TEXT_UI } from '../layout.js';
import { useViewMessages } from '../MessagesProvider.js';
import { AxesTab } from './AxesTab.js';
import { DataTab } from './DataTab.js';
import { DisplayTab, TableDisplay } from './DisplayTab.js';
import type { OptionsPageProps, OptionsShape } from './optionControls.js';

export interface ChartOptionsProps {
  picked: Picked;
  /** The chart, fitted to the shape that ran. */
  chart: ChartSpec;
  /** The config the rows were shaped by: what the slots may name. */
  groups: readonly AnalysisGroup[];
  metrics: readonly AnalysisMetric[];
  /** The result's columns, which title every alias and show every value. */
  columns: readonly AnalysisColumnView[];
  rows: readonly RecordData[];
  onChange(chart: ChartSpec): void;
  /** The table's one option, which is a query of its own and runs at once. */
  totals: boolean;
  onTotals(on: boolean): void;
  /** Back to the chart types. */
  onBack(): void;
  /** This level's heading, which the keyboard is put on (A1, `ChartPicker`). */
  headingRef?: RefObject<HTMLHeadingElement | null>;
  /** Why a derived line cannot be drawn over the rows on screen (Q53). */
  gapOf?: OptionsPageProps['gapOf'];
  /** A moving average's window when none is typed. */
  defaultWindow?: number;
}

/**
 * The visualization panel's second level (D20 屏 J): the options of the
 * chart type in force, on up to three pages — data, display, axes — the
 * same pages whichever family it is, with each family filling them with
 * its own slots and settings. A change here redraws the rows on screen;
 * none sends a query.
 */
export function ChartOptions({
  picked,
  chart,
  groups,
  metrics,
  columns,
  rows,
  onChange,
  totals,
  onTotals,
  onBack,
  headingRef,
  gapOf,
  defaultWindow,
}: ChartOptionsProps) {
  const messages = useViewMessages();
  // Every slot's choices are named as the result's columns are titled —
  // 「金额的总和」, never the alias — and a funnel's stages as the group's
  // values show. Both read the catalogue this panel is drawn under.
  const column = useColumnTitle(columns);
  const label = useValueLabel(columns);
  const shape = useMemo<OptionsShape>(() => {
    // The metrics that are moments, which a mark never measures.
    const moments = momentColumns(columns);
    const choices = metrics.map(metric => ({
      value: metric.alias,
      label: column(metric.alias) ?? metric.alias,
    }));
    return {
      groups: groups.map(group => ({
        value: group.alias,
        label: column(group.alias) ?? group.alias,
      })),
      metrics: choices,
      quantities: choices.filter(choice => !moments.has(choice.value)),
      moments,
      additive: new Set(
        metrics.filter(isAdditiveMetric).map(metric => metric.alias),
      ),
      measures: measureColumns(columns),
      dated: new Set(
        columns
          .filter(column => column.dateUnit !== undefined)
          .map(column => column.alias),
      ),
    };
  }, [groups, metrics, columns, column]);
  const page = { chart, shape, rows, label, onChange, gapOf, defaultWindow };
  const tabs = optionTabs(picked);
  const [tab, setTab] = useState<OptionsTab>(tabs[0] ?? 'data');
  const current = tabs.includes(tab) ? tab : (tabs[0] ?? 'data');
  const name = messages.label(
    picked === 'table' ? 'label.layout.table' : `label.chart.type.${picked}`,
  );
  const pageOf = (which: OptionsTab) =>
    picked === 'table' ? (
      <TableDisplay
        totals={totals}
        whole={groups.length === 0}
        onTotals={onTotals}
      />
    ) : which === 'data' ? (
      <DataTab {...page} />
    ) : which === 'display' ? (
      <DisplayTab {...page} />
    ) : (
      <AxesTab {...page} />
    );
  return (
    <div
      data-slot="chart-options"
      data-chart-type={picked}
      className={cn('flex flex-col gap-3 p-3', TEXT_UI)}
    >
      <div className="flex items-center gap-2">
        <IconButton
          label={messages.label('label.chart.options-back')}
          variant="ghost"
          size="icon-sm"
          onClick={onBack}
        >
          <ArrowLeftIcon />
        </IconButton>
        <h2 ref={headingRef} tabIndex={-1} className={LANDING_HEADING}>
          {messages.label('label.chart.options', { name })}
        </h2>
      </div>
      {tabs.length > 1 ? (
        <Tabs
          value={current}
          onValueChange={next => {
            if (typeof next === 'string' && tabs.includes(next as OptionsTab))
              setTab(next as OptionsTab);
          }}
        >
          <TabsList className="w-full">
            {tabs.map(which => (
              <TabsTrigger key={which} value={which}>
                {messages.label(`label.chart.tab.${which}`)}
              </TabsTrigger>
            ))}
          </TabsList>
          {tabs.map(which => (
            <TabsContent
              key={which}
              value={which}
              className={cn('flex flex-col gap-3', TEXT_UI)}
            >
              {pageOf(which)}
            </TabsContent>
          ))}
        </Tabs>
      ) : (
        <div className="flex flex-col gap-3">{pageOf(current)}</div>
      )}
    </div>
  );
}
