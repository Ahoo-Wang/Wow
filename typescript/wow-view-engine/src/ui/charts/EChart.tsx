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
  createContext,
  useContext,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { cn } from 'cn';
import { useSurfaceTheme, useSurfaceTokens } from '../ViewSurface.js';
import type { ZoomWindow } from './cartesianZoom.js';
import { loadCharts, loadedCharts } from './load.js';
import { usePatterns, withPatterns } from './patterns.js';
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

/**
 * Whether a menu stands over the chart — the follow-up menu on a pressed
 * group (`DrillMenu`). The press puts the tooltip away, but the pointer is
 * still on the mark: the least move before the menu's backdrop is up raised
 * it again, and nothing afterwards reached the chart to take it down, so it
 * sat over the menu's first items (2026-09-24 walk). While this holds, the
 * tooltip is not drawn at all; it comes back with the next pointer over the
 * chart once the menu has gone.
 */
export const ChartMenuOpen = createContext(false);

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
   * the marks move rather than grow in again — and as it is zoomed, handed
   * the part of the axis on screen (`window`), so a year narrowed to a week
   * names every day again.
   */
  adapt?: (
    width: number,
    height: number,
    window?: ZoomWindow,
  ) => EChartsCoreOption | undefined;
  /**
   * What a zoom belongs to: while this stays the same, a zoom survives a
   * redraw — a theme switch, a series switched off in the legend; when it
   * changes — a new result — the chart opens at its whole range again. A
   * zoom is never saved (D33 Q51); it lives in this binding and nowhere
   * else.
   */
  zoomFor?: unknown;
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
 * mounts and again whenever the surface's mode or the tokens it reads change
 * (`useSurfaceTheme`, `useSurfaceTokens`: one observer up the tree watches
 * `class`, `data-theme`, `data-fve-preset` and `style`), so a switch to dark
 * or to another set of `--fve-*` redraws in place rather than remounting.
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
  zoomFor,
}: EChartProps) {
  const plot = useRef<HTMLDivElement>(null);
  const frame = useRef<HTMLDivElement>(null);
  const library = useChartLibrary();
  const mode = useSurfaceTheme();
  const tokens = useSurfaceTokens();
  const [theme, setTheme] = useState<ChartTheme>();
  useLayoutEffect(() => {
    if (!plot.current) return;
    const next = readChartTheme(plot.current);
    setTheme(previous => (previous?.key === next.key ? previous : next));
  }, [mode, tokens]);

  const chart = useRef<ECharts>(undefined);
  const menuOpen = useContext(ChartMenuOpen);
  useEffect(() => {
    if (menuOpen) chart.current?.dispatchAction({ type: 'hideTip' });
  }, [menuOpen]);
  const size = useRef({ width: 0, height: 0 });
  // Patterns over the colours (decal, D33 Q57): the host's pin on the
  // theme, else the reader's system asking for more contrast.
  const systemPatterns = usePatterns();
  const patterned = theme?.patterns ?? systemPatterns;
  const zoom = useRef<{ for: unknown; window?: ZoomWindow }>({ for: zoomFor });
  const latest = useRef({ option, adapt, onClick, theme, patterned, zoomFor });
  useLayoutEffect(() => {
    latest.current = { option, adapt, onClick, theme, patterned, zoomFor };
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
        draw(
          chart.current,
          undefined,
          latest.current.adapt?.(w, h, zoom.current.window),
        );
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
      // A zoom is kept here — for the next redraw of the same result — and
      // the categories refitted to it, once a frame at most while it moves —
      // on a timer, not an animation frame, which a hidden page never runs.
      let refit: ReturnType<typeof setTimeout> | undefined;
      created.on('datazoom', params => {
        // Off the event: the option read back at this moment can still hold
        // the window before it, which fitted a week as if it were a month.
        const window =
          zoomWindow(params as ZoomEvent) ??
          zoomWindow(created.getOption() as ZoomEvent);
        zoom.current = { for: latest.current.zoomFor, window };
        frame.current?.setAttribute('data-zoomed', String(zoomed(window)));
        clearTimeout(refit);
        refit = setTimeout(() => {
          const { width, height } = size.current;
          const adjustment = latest.current.adapt?.(width, height, window);
          if (adjustment && !created.isDisposed())
            created.setOption(adjustment);
        }, 16);
      });
      chart.current = created;
      const now = latest.current;
      if (now.theme)
        draw(
          created,
          composed(now.option(now.theme), now.patterned, undefined),
          now.adapt?.(w, h),
        );
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
      // A new result opens at its whole range; anything else keeps the zoom.
      if (zoom.current.for !== zoomFor) {
        zoom.current = { for: zoomFor };
        frame.current?.removeAttribute('data-zoomed');
      }
      const window = zoom.current.window;
      draw(
        chart.current,
        composed(option(theme), patterned, window),
        adapt?.(size.current.width, size.current.height, window),
      );
    }
  }, [theme, option, adapt, patterned, zoomFor]);

  // A plain wheel over the plot is the page's: the library's roam handler
  // takes every wheel over the grid — and stops it — before asking whether
  // the zoom wanted it, so without this a long chart swallowed the page's
  // scroll. Only a wheel with Ctrl held (a trackpad pinch sends one) goes
  // on to the chart, where a zoom that takes gestures narrows to it.
  useLayoutEffect(() => {
    const element = plot.current?.parentElement;
    if (!element) return;
    const guard = (event: WheelEvent) => {
      if (!event.ctrlKey) event.stopPropagation();
    };
    element.addEventListener('wheel', guard, { capture: true, passive: true });
    return () => element.removeEventListener('wheel', guard, { capture: true });
  }, []);

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
      data-menu-open={menuOpen || undefined}
      data-patterns={patterned ? 'on' : 'off'}
      {...data}
      className={cn(
        'flex aspect-video min-h-52 w-full gap-2 text-xs',
        // The tooltip the library draws is ours (`tooltipHtml`), inside its
        // own transparent box: hidden, nothing of it is on screen.
        'data-menu-open:[&_[data-slot=chart-tooltip]]:invisible',
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
 * The drawing as it goes to the library: the family's option, patterns over
 * its colours where they are asked for, and a zoom kept from before put back
 * on its slider — a whole new option replaces the last one (`notMerge`), and
 * would otherwise open every redraw at the whole range again.
 */
export function composed(
  option: EChartsCoreOption,
  patterned: boolean,
  window: ZoomWindow | undefined,
): EChartsCoreOption {
  const drawn = patterned ? withPatterns(option) : option;
  const zooms = drawn.dataZoom;
  if (!window || !Array.isArray(zooms)) return drawn;
  return {
    ...drawn,
    dataZoom: zooms.map((entry: object) => ({ ...entry, ...window })),
  };
}

/**
 * What a zoom event or a drawing says the window is: a slider's event
 * carries it, an inside zoom's carries a batch of them, and a drawing lists
 * its zooms (`dataZoom`), all moving the one axis together.
 */
export interface ZoomEvent {
  start?: unknown;
  end?: unknown;
  batch?: unknown;
  dataZoom?: unknown;
}

/** The window a zoom event or a drawing stands at, in percent. */
export function zoomWindow(said: ZoomEvent): ZoomWindow | undefined {
  const { start, end } = said;
  if (typeof start === 'number' && typeof end === 'number')
    return { start, end };
  const list = Array.isArray(said.batch) ? said.batch : said.dataZoom;
  const first: unknown = Array.isArray(list) ? list[0] : undefined;
  return typeof first === 'object' && first !== null
    ? zoomWindow({
        ...(first as ZoomEvent),
        batch: undefined,
        dataZoom: undefined,
      })
    : undefined;
}

/** Whether a window leaves any of the axis out of sight. */
function zoomed(window: ZoomWindow | undefined): boolean {
  return window !== undefined && (window.start > 0 || window.end < 100);
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
