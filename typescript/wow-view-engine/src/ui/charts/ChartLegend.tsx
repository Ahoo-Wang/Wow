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
 */
export function ChartLegend({
  entries,
  at,
  lead,
}: {
  entries: readonly LegendEntry[];
  at: 'top' | 'bottom' | 'right';
  /** What comes before the entries, when the family has something to say. */
  lead?: ReactNode;
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
        className={cn(
          'relative flex min-w-0 gap-x-4 gap-y-1',
          at === 'right' ? 'flex-col' : 'flex-1 flex-wrap items-center',
          folds && !open && 'max-h-4 overflow-hidden',
        )}
      >
        {lead}
        {entries.map(entry => (
          <li
            key={entry.key}
            data-slot="chart-legend-item"
            className="flex h-4 min-w-0 items-center gap-1.5"
          >
            <span
              aria-hidden
              className="size-2 shrink-0 rounded-full"
              style={{ background: entry.color }}
            />
            <span className="truncate">{entry.label}</span>
          </li>
        ))}
      </ul>
      {folds && (open || hidden > 0) && (
        <Button
          data-slot="chart-legend-more"
          variant="link"
          size="xs"
          className="h-4 shrink-0 px-0"
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
