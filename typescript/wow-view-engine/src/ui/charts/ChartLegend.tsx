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

import { useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { cn } from 'cn';
import { Button } from '../components/button.js';
import { useViewMessages } from '../MessagesProvider.js';

export interface LegendEntry {
  key: string;
  label: string;
  /** A CSS colour: a theme slot or the one the spec pinned. */
  color: string;
  /** A number beside the name — a slice's share — in figures that align. */
  value?: string;
  /** Switched off in the legend: not drawn, still listed (`onToggle`). */
  hidden?: boolean;
  /**
   * A computed line rather than a series: a dash in the text's own ink
   * stands for it where a series has its dot (D33 batch B).
   */
  dashed?: 'dashed' | 'dotted';
}

/**
 * The legend, as text beside the drawing rather than inside it.
 *
 * It is the page's own type — it can be selected and read, and it never
 * overlaps the plot, which a legend the library draws into its canvas does
 * once the names run long. A dot per series, as Metabase draws its legend;
 * top and left-aligned by default, so it reads before the marks do.
 *
 * Above or below the plot it keeps to one line: the entries that do not
 * fit are counted at its end — 「还有 3 个」 — and a press opens the whole
 * list, as Metabase folds its own. Twenty split values would otherwise push
 * the plot down to a sliver in a dashboard panel. Beside the plot it is a
 * column that scrolls.
 *
 * Given `onToggle`, each entry is a switch for its series (D33 batch A): a
 * button whose `aria-pressed` says whether the series is drawn, reached by
 * Tab and pressed by Enter or Space like any button — the library's own
 * legend is drawn into the picture and no keyboard reaches it.
 */
export function ChartLegend({
  entries,
  at,
  lead,
  onToggle,
}: {
  entries: readonly LegendEntry[];
  at: 'top' | 'bottom' | 'right';
  /** What comes before the entries, when the family has something to say. */
  lead?: ReactNode;
  /**
   * Makes each entry a switch for its series: pressed, the series is drawn;
   * pressed again, it is not. Left out, the entries are text.
   */
  onToggle?: (key: string) => void;
}) {
  const messages = useViewMessages();
  const list = useRef<HTMLUListElement>(null);
  const [open, setOpen] = useState(false);
  const [hidden, setHidden] = useState(0);
  const folds = at !== 'right';
  useLayoutEffect(() => {
    const element = list.current;
    if (!element || !folds) return;
    // An entry is out of sight when it wrapped below the first one's line.
    const count = () => {
      const items = [...element.children] as HTMLElement[];
      const top = items[0]?.offsetTop ?? 0;
      setHidden(items.filter(item => item.offsetTop > top).length);
    };
    count();
    const observer = new ResizeObserver(count);
    observer.observe(element);
    return () => observer.disconnect();
  }, [entries, folds]);

  // A switch is a control, and a control is a target a finger can hit
  // (WCAG 2.5.8): a line of switches is 24px tall where a line of text is 16.
  const switches = onToggle !== undefined;
  return (
    <div
      data-slot="chart-legend"
      data-open={open || undefined}
      className={cn(
        'text-muted-foreground flex min-w-0 gap-2',
        at === 'right'
          ? 'max-w-[40%] shrink-0 flex-col justify-center overflow-y-auto'
          : 'items-start',
      )}
    >
      <ul
        ref={list}
        aria-label={
          switches ? messages.label('label.chart.legend.toggle') : undefined
        }
        className={cn(
          'relative flex min-w-0 gap-y-1',
          switches ? 'gap-x-1' : 'gap-x-4',
          at === 'right' ? 'flex-col' : 'flex-1 flex-wrap items-center',
          folds && !open && 'overflow-hidden',
          folds && !open && (switches ? 'max-h-6' : 'max-h-4'),
        )}
      >
        {lead}
        {entries.map(entry => (
          <li
            key={entry.key}
            data-slot="chart-legend-item"
            data-hidden={entry.hidden || undefined}
            className={cn(
              'flex min-w-0 items-center gap-1.5',
              switches ? 'h-6' : 'h-4',
            )}
          >
            {switches ? (
              <Button
                data-slot="chart-legend-toggle"
                variant="ghost"
                size="xs"
                aria-pressed={!entry.hidden}
                className="min-w-0"
                onClick={() => onToggle(entry.key)}
              >
                <EntryText entry={entry} />
              </Button>
            ) : (
              <EntryText entry={entry} />
            )}
          </li>
        ))}
      </ul>
      {folds && (open || hidden > 0) && (
        <Button
          data-slot="chart-legend-more"
          variant="link"
          size="xs"
          className={cn('shrink-0 px-0', switches ? 'h-6' : 'h-4')}
          aria-expanded={open}
          onClick={() => setOpen(!open)}
        >
          {open
            ? messages.label('label.chart.legend.less')
            : messages.label('label.chart.legend.more', { count: hidden })}
        </Button>
      )}
    </div>
  );
}

/**
 * An entry's dot, name and number. A series switched off keeps its place and
 * its name — the legend still says what the colour stood for — with its dot
 * drawn as a ring and its name struck through, so it reads as not drawn.
 */
function EntryText({ entry }: { entry: LegendEntry }) {
  return (
    <>
      {entry.dashed ? (
        <span
          aria-hidden
          data-slot="chart-legend-dash"
          data-stroke={entry.dashed}
          className={cn(
            'text-foreground w-3 shrink-0 border-t-2 border-current',
            entry.dashed === 'dotted' ? 'border-dotted' : 'border-dashed',
            entry.hidden && 'opacity-50',
          )}
        />
      ) : (
        <span
          aria-hidden
          data-slot="chart-legend-dot"
          className="size-2 shrink-0 rounded-full border-2"
          style={{
            borderColor: entry.color,
            background: entry.hidden ? 'transparent' : entry.color,
          }}
        />
      )}
      <span className={cn('truncate', entry.hidden && 'line-through')}>
        {entry.label}
      </span>
      {entry.value !== undefined && (
        <span className="text-foreground ml-auto pl-1 tabular-nums">
          {entry.value}
        </span>
      )}
    </>
  );
}
