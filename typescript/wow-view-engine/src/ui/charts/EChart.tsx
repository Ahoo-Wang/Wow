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

import type { EChartsCoreOption, ECharts } from 'echarts/core';
import {
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { cn } from 'cn';
import { useSurfaceTheme } from '../ViewSurface.js';
import { loadCharts, loadedCharts } from './load.js';
import { readChartTheme, type ChartTheme } from './theme.js';

/** Where a legend drawn beside the plot stands. */
export type LegendPlace = 'top' | 'bottom' | 'right';

/**
 * The narrowest frame a legend stands beside the plot in, in pixels. Beside
 * a plot on a phone the legend takes two fifths of the width, and a pie
 * squeezed into the rest had no room left for its labels — the library cut
 * 47.7% to 「4」 (audit P0-6). Under the plot, the pie gets the width, as
 * Metabase moves a narrow chart's legend below it.
 */
export const LEGEND_BESIDE_MIN = 480;

/** What a press on a mark hands back: which one, and where the pointer was. */
export interface ChartClick {
  componentType: string;
  seriesIndex?: number;
  dataIndex: number;
  /** The datum pressed, as the series holds it: a heatmap cell's `[x, y, …]`. */
  value?: unknown;
  /** The pointer event under the press, for the menu to hang from. */
  event?: { event?: { clientX: number; clientY: number } };
}

export interface EChartProps {
  /** What is drawn, in one line: the name of the `role="img"`. */
  name: string;
  className?: string;
  /** The drawing for a theme; rebuilt when the theme or this changes. */
  option: (theme: ChartTheme) => EChartsCoreOption;
  /**
   * What the plot's size changes: a category axis turns its names at a
   * slant once they no longer fit side by side; a pie makes room for its
   * labels or leaves them out. Merged into the drawing as it resizes, so
   * the marks move rather than grow in again.
   */
  adapt?: (width: number, height: number) => EChartsCoreOption | undefined;
  /** A press on a mark; left out, the marks are not pressable. */
  onClick?: (click: ChartClick) => void;
  /**
   * Drawn above the plot, beside it (`right`) or under it. Beside it only
   * where the frame has room (`LEGEND_BESIDE_MIN`): narrower, it goes under
   * — so the node is drawn for where it lands.
   */
  legend?: {
    at: LegendPlace;
    node: ReactNode | ((placed: LegendPlace) => ReactNode);
  };
  /** Said on the frame, for whoever reads the drawing's state back. */
  data?: Record<`data-${string}`, string | number | undefined>;
  /**
   * The widest the plot grows beside a legend on its right, as a multiple
   * of its height; the two are then centred in the frame together. A pie is
   * a circle: in a wide frame the plot took all the width the legend left,
   * the pie stood in its middle and the legend at the far edge, a hand's
   * width from the slices it names (2026-09-23 audit). Left out, the plot
   * takes all the room there is.
   */
  hug?: number;
}

/**
 * One chart, drawn by the library into an element of its own.
 *
 * The frame is ours and the drawing is the library's: `data-slot="chart"`
 * lays out the legend and the plot, and the plot is one `role="img"` named
 * by what it draws — the library's own accessibility layer stays off, and the
 * numbers are said by `ChartReadingTable` beside it. Nothing in the drawing
 * takes focus; the keyboard's way to a group is the table layout (F10).
 *
 * The rest is the thin binding the design asks for rather than a wrapper
 * package (D21): the library is created once the element has a size, told
 * every new size, handed a whole new option whenever the option changes
 * (`notMerge`, so a series taken away is gone rather than left behind), and
 * disposed with the element. The theme is read off the element when it
 * mounts and again whenever the surface's mode changes (`useSurfaceTheme`,
 * whose observer already watches `class` and `data-theme` up the tree), so
 * a switch to dark redraws in place rather than remounting.
 */
export function EChart({
  name,
  className,
  option,
  adapt,
  onClick,
  legend,
  data,
  hug,
}: EChartProps) {
  const plot = useRef<HTMLDivElement>(null);
  const frame = useRef<HTMLDivElement>(null);
  const library = useChartLibrary();
  const mode = useSurfaceTheme();
  const [theme, setTheme] = useState<ChartTheme>();
  useLayoutEffect(() => {
    if (!plot.current) return;
    const next = readChartTheme(plot.current);
    setTheme(previous => (previous?.key === next.key ? previous : next));
  }, [mode]);

  const chart = useRef<ECharts>(undefined);
  const size = useRef({ width: 0, height: 0 });
  const latest = useRef({ option, adapt, onClick, theme });
  useLayoutEffect(() => {
    latest.current = { option, adapt, onClick, theme };
  });

  // The frame's width, not the plot's: the plot widens when the legend
  // leaves its side, and measured by the plot the legend would come back.
  const beside = legend?.at === 'right';
  const [narrow, setNarrow] = useState(false);
  const [tall, setTall] = useState(0);
  useLayoutEffect(() => {
    const element = frame.current;
    if (!beside || !element) return;
    const measure = ({ width, height }: { width: number; height: number }) => {
      // Nothing laid out yet — or jsdom, which lays out nothing — says
      // nothing about the room.
      if (width > 0) setNarrow(width < LEGEND_BESIDE_MIN);
      if (height > 0) setTall(height);
    };
    // Before the first paint, so a phone never sees the legend move.
    measure(element.getBoundingClientRect());
    const observer = new ResizeObserver(([entry]) =>
      measure(entry.contentRect),
    );
    observer.observe(element);
    return () => observer.disconnect();
  }, [beside]);
  const placed: LegendPlace | undefined =
    beside && narrow ? 'bottom' : legend?.at;
  const hugged = hug !== undefined && placed === 'right' && tall > 0;
  const legendNode =
    legend && placed
      ? typeof legend.node === 'function'
        ? legend.node(placed)
        : legend.node
      : undefined;

  useLayoutEffect(() => {
    const element = plot.current;
    if (!library || !element) return;
    const observer = new ResizeObserver(([entry]) => {
      const { width: w, height: h } = entry.contentRect;
      if (!w || !h) return;
      size.current = { width: w, height: h };
      if (chart.current) {
        chart.current.resize({ width: w, height: h });
        draw(chart.current, undefined, latest.current.adapt?.(w, h));
        return;
      }
      const created = library.init(element, null, {
        renderer: 'svg',
        width: w,
        height: h,
      });
      created.on('click', params => {
        const handler = latest.current.onClick;
        if (!handler) return;
        // A press opens the follow-up menu at the point pressed, and the
        // tooltip standing there sat on top of it, over its first items.
        // The menu is the answer to the press; the tooltip steps aside.
        created.dispatchAction({ type: 'hideTip' });
        handler(params as unknown as ChartClick);
      });
      // Said on the frame once the marks have landed — animation included —
      // and taken back while a new drawing is on its way, for whoever reads
      // the drawing's geometry: a bar measured as it grows is a bar of
      // nothing. Written to the element, not to state: it is a fact about
      // the pixels, and nothing React draws depends on it.
      created.on('finished', () =>
        frame.current?.setAttribute('data-drawn', 'true'),
      );
      chart.current = created;
      const { theme: now, option: build, adapt: fit } = latest.current;
      if (now) draw(created, build(now), fit?.(w, h));
    });
    observer.observe(element);
    return () => {
      observer.disconnect();
      chart.current?.dispose();
      chart.current = undefined;
    };
  }, [library]);

  useLayoutEffect(() => {
    if (chart.current && theme) {
      frame.current?.removeAttribute('data-drawn');
      draw(
        chart.current,
        option(theme),
        adapt?.(size.current.width, size.current.height),
      );
    }
  }, [theme, option, adapt]);

  const plotted = (
    <div
      data-slot="chart-plot"
      role="img"
      aria-label={name}
      className="relative min-h-0 min-w-0 flex-1"
      style={hugged ? { maxWidth: Math.round(tall * (hug ?? 1)) } : undefined}
    >
      {/* Positioned inline: the library makes its element `relative` unless
          it already computes as positioned, and a stylesheet that has not
          arrived yet would lose it the plot's size. */}
      <div ref={plot} style={{ position: 'absolute', inset: 0 }} />
    </div>
  );
  return (
    <div
      ref={frame}
      data-slot="chart"
      data-legend={placed ?? 'none'}
      {...data}
      className={cn(
        'flex aspect-video min-h-52 w-full gap-2 text-xs',
        placed === 'right' ? 'flex-row' : 'flex-col',
        hugged && 'justify-center',
        className,
      )}
    >
      {placed === 'top' && legendNode}
      {plotted}
      {placed !== undefined && placed !== 'top' && legendNode}
    </div>
  );
}

/**
 * A whole option replaces the last one, the width's adjustment folded in
 * before it is drawn — drawn first and adjusted after, the category names
 * showed one frame flat and piled on each other before turning. A resize
 * alone merges the adjustment over what is drawn, so the marks move rather
 * than grow in again.
 */
function draw(
  chart: ECharts,
  option: EChartsCoreOption | undefined,
  adjustment: EChartsCoreOption | undefined,
) {
  if (option)
    chart.setOption(adjustment ? merged(option, adjustment) : option, {
      notMerge: true,
    });
  else if (adjustment) chart.setOption(adjustment);
}

/**
 * `over` laid onto `base`, object by object, and a list of objects — the
 * series — item by item, as the library merges an option handed to a
 * drawing it already holds: a pie's radius adjusted on a resize and the
 * same adjustment folded into a new drawing land alike. Anything else
 * replaces, a list of numbers included.
 */
export function merged(
  base: EChartsCoreOption,
  over: EChartsCoreOption,
): EChartsCoreOption {
  const out: EChartsCoreOption = { ...base };
  for (const [key, value] of Object.entries(over)) {
    const under = out[key];
    out[key] =
      isPlain(under) && isPlain(value)
        ? merged(under, value)
        : isPlainList(under) && isPlainList(value)
          ? Array.from(
              { length: Math.max(under.length, value.length) },
              (_, index) =>
                value[index] === undefined
                  ? under[index]
                  : under[index] === undefined
                    ? value[index]
                    : merged(under[index], value[index]),
            )
          : value;
  }
  return out;
}

function isPlainList(value: unknown): value is EChartsCoreOption[] {
  return Array.isArray(value) && value.length > 0 && value.every(isPlain);
}

function isPlain(value: unknown): value is EChartsCoreOption {
  return (
    typeof value === 'object' &&
    value !== null &&
    Object.getPrototypeOf(value) === Object.prototype
  );
}

/**
 * The library, once its chunk has arrived. A failed load is thrown in
 * render, so the chart's `RenderBoundary` says why and offers to try again.
 */
function useChartLibrary() {
  const [library, setLibrary] = useState(loadedCharts);
  const [failure, setFailure] = useState<{ error: unknown }>();
  useEffect(() => {
    if (library) return;
    let live = true;
    loadCharts().then(
      loaded => live && setLibrary(() => loaded),
      (error: unknown) => live && setFailure({ error }),
    );
    return () => {
      live = false;
    };
  }, [library]);
  if (failure) throw failure.error;
  return library;
}
