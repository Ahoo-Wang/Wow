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

import { useEffect, useRef, type ReactElement, type RefObject } from 'react';
import { derivedGap, movingWindow } from '../../analysis/index.js';
import type { ChartSpec, RecordData } from '../../model/index.js';
import type { AnalysisResultController } from '../../react/index.js';
import { ChartOptions } from './ChartOptions.js';
import { ChartPicker } from './ChartPicker.js';
import type { OptionsPageProps } from './optionControls.js';

/** The visualization panel's two levels: the chart types, then the options. */
export type VisualizationLevel = 'picker' | 'options';

/** Where the keyboard is put as the panel's level moves. */
export interface VisualizationFocus {
  /** The heading of the level on screen. */
  heading: RefObject<HTMLHeadingElement | null>;
  /** The picker's options button: where the options page is left back to. */
  optionsButton: RefObject<HTMLButtonElement | null>;
  /** The control that opened the panel: where closing it hands back to. */
  visualizeRef: RefObject<HTMLButtonElement | null>;
}

/**
 * The keyboard follows the level (A1).
 *
 * The panel replaces its own contents as the level changes, so every one of
 * those changes would leave the focus on an element that is no longer on the
 * page — the press that opened it, the options button, the way back — and a
 * focus with nothing under it falls to `<body>`. Each level therefore takes
 * the keyboard to its own heading, except coming back from the options,
 * which lands on the options button the user left by; closing the panel
 * (`null`, where a host can close it) hands it back to the button that
 * opened it. Both are where the user was.
 *
 * It is an effect keyed on the level rather than anything done inside the
 * press: the heading does not exist until React has drawn the level, and a
 * timer waiting for that would be a guess.
 */
export function useVisualizationFocus(
  level: VisualizationLevel | null,
): VisualizationFocus {
  const heading = useRef<HTMLHeadingElement | null>(null);
  const optionsButton = useRef<HTMLButtonElement | null>(null);
  const visualizeRef = useRef<HTMLButtonElement | null>(null);
  const was = useRef(level);
  useEffect(() => {
    const before = was.current;
    if (before === level) return;
    was.current = level;
    if (level !== null) {
      if (before === 'options' && level === 'picker' && optionsButton.current)
        optionsButton.current.focus();
      else heading.current?.focus();
      return;
    }
    if (before !== null) visualizeRef.current?.focus();
  }, [level]);
  return { heading, optionsButton, visualizeRef };
}

export interface VisualizationPanelProps {
  /** The level on screen; nothing is drawn for `null`. */
  level: VisualizationLevel | null;
  focus: VisualizationFocus;
  result: Pick<
    AnalysisResultController,
    | 'fits'
    | 'picked'
    | 'choose'
    | 'chart'
    | 'columns'
    | 'view'
    | 'question'
    | 'chartData'
  >;
  /** Whether the totals row is on, and the way it is switched. */
  totals: boolean;
  onTotals(on: boolean): void;
  /** A chart the options page wrote. */
  onChange(chart: ChartSpec): void;
  /** A move between the two levels. */
  onLevel(level: VisualizationLevel): void;
  /** The picker's way back, where the host can close the panel. */
  onBack?(): void;
  /** What that way back is called (`ChartPickerProps.backLabel`). */
  backLabel?: string;
}

/**
 * The visualization panel (D20 屏 I／J) as the workbench and a dashboard
 * panel's own look both draw it: the chart types, then the chosen type's
 * options, both fitting the question's shape. The options stand over the
 * question, so they work before the first answer and after it failed: the
 * slots name its columns, and what reads rows — a funnel's stages in the
 * order they came — has none yet. With no question the options level draws
 * nothing.
 *
 * A function rather than a component, so a host reads the one it gets back
 * as React reads a child: `null` is no panel at all (the sidebar's column
 * shows the list instead).
 */
export function visualizationPanel({
  level,
  focus,
  result,
  totals,
  onTotals,
  onChange,
  onLevel,
  onBack,
  backLabel,
}: VisualizationPanelProps): ReactElement | null {
  const question = result.question;
  if (level === 'picker')
    return (
      <ChartPicker
        headingRef={focus.heading}
        optionsRef={focus.optionsButton}
        fits={result.fits}
        picked={result.picked}
        onPick={result.choose}
        onOptions={() => onLevel('options')}
        {...(onBack ? { onBack } : {})}
        {...(backLabel === undefined ? {} : { backLabel })}
      />
    );
  if (level === 'options' && question) {
    // What the rows on screen can carry a derived line over (Q53): judged
    // against the chart as drafted, over the chart it drew. Before any rows
    // there is nothing to judge, and every box is open.
    const drafted = { ...question, chart: result.chart };
    const data =
      result.chartData?.type === 'cartesian' ? result.chartData : undefined;
    const view = result.view;
    const cutShort =
      view !== null && (view.truncated || view.atLimit !== undefined);
    const gapOf: OptionsPageProps['gapOf'] =
      data &&
      (derived => {
        const gap = derivedGap(drafted, data, derived, cutShort);
        return gap && { gap, limit: drafted.limit };
      });
    return (
      <ChartOptions
        headingRef={focus.heading}
        picked={result.picked}
        chart={result.chart}
        groups={question.groups}
        metrics={question.metrics}
        columns={result.columns}
        rows={result.view?.rows ?? NO_ROWS}
        totals={totals}
        onTotals={onTotals}
        onChange={onChange}
        onBack={() => onLevel('picker')}
        gapOf={gapOf}
        defaultWindow={movingWindow(drafted, {})}
      />
    );
  }
  return null;
}

/** A command a host that cannot take it answers with: nothing happens. */
export function ignore(): void {}

/** Stable identity for "no rows yet", so the options' memos stay quiet. */
const NO_ROWS: readonly RecordData[] = [];
