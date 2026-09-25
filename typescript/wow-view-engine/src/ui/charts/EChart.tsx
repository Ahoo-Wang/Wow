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
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type CSSProperties,
  type ReactNode,
} from 'react';
import { cn } from 'cn';
import { useViewMessages } from '../MessagesProvider.js';
import { useSurfaceTheme, useSurfaceTokens } from '../ViewSurface.js';
import { BRUSH_CLEAR, BRUSH_CURSOR } from './cartesianBrush.js';
import type { LegendEntry } from './ChartLegend.js';
import { ChartFailure } from './failure.js';
import { ChartImageTarget, pictureTheme } from './image.js';
import type { ZoomWindow } from './cartesianZoom.js';
import { loadCharts, loadedCharts, type ChartChunk } from './load.js';
import { merged } from './optionMerge.js';
import { usePatterns, withPatterns } from './patterns.js';
import { watchSize } from './sizes.js';
import { usePrinting } from './print.js';
import { CHART_FALLBACK, readChartTheme, type ChartTheme } from './theme.js';

/**
 * What a size changes about the drawing (`adapt`), measured at the type of
 * the theme in force — or the built-in type, before any is read.
 */
const fitTo = (
  now: Pick<EChartProps, 'adapt'> & { theme?: ChartTheme },
  width: number,
  height: number,
  window?: ZoomWindow,
) => now.adapt?.(width, height, window, now.theme?.text ?? CHART_FALLBACK.text);

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

/**
 * The drawing in one sentence (`chartSentence`), said after its name as
 * its description: how many groups, the highest and the lowest. Handed
 * down by `AnalysisChart`, which reads it off the same data the marks are.
 */
export const ChartSentence = createContext<string | undefined>(undefined);

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
   * names every day again. Handed the chart's type (`ChartTheme.text`), so
   * what it measures is measured at the size it is drawn at.
   */
  adapt?: (
    width: number,
    height: number,
    window: ZoomWindow | undefined,
    text: ChartTheme['text'],
  ) => EChartsCoreOption | undefined;
  /**
   * What a zoom belongs to: while this stays the same, a zoom survives a
   * redraw — a theme switch, a series switched off in the legend; when it
   * changes — a new result — the chart opens at its whole range again. A
   * zoom is never saved (D33 Q51); it lives in this binding and nowhere
   * else.
   */
  zoomFor?: unknown;
  /**
   * A press on a mark; left out, the marks are not pressable. On a touch
   * screen the first tap on a mark shows its tooltip, which says to tap
   * again (`label.drill.tap-again`), and the second tap on the same mark
   * is the press: a tap that opened the menu at once left the tooltip no
   * moment to be read (analysis-echarts.md 2.3).
   */
  onClick?: (click: ChartClick) => void;
  /**
   * A finished brush (`brushOption`, D33 Q52): the library's `brushEnd`
   * event as it came, and where the pointer let go, for a menu to hang
   * from. The cover stays while a menu stands over the chart
   * (`ChartMenuOpen`) and goes when it closes.
   */
  onBrush?: (said: unknown, at: { clientX: number; clientY: number }) => void;
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
   * The legend's entries as a picture of the chart draws them (D33 Q58):
   * the ones on screen, a switched-off series left out. Left out where the
   * chart shows no legend.
   */
  legendEntries?: readonly LegendEntry[];
  /**
   * The widest the plot grows beside a legend on its right, as a multiple
   * of its height; the two are then centred in the frame together. A pie is
   * a circle: in a wide frame the plot took all the width the legend left,
   * the pie stood in its middle and the legend at the far edge, a hand's
   * width from the slices it names (2026-09-23 audit). Left out, the plot
   * takes all the room there is.
   */
  hug?: number;
  /**
   * The plot's own height, for a family whose drawing is sized by what it
   * holds rather than by the frame's width — a funnel, by its stages. The
   * frame then drops its 16:9 aspect and grows with its legend around the
   * plot. Left out, the plot takes what the frame leaves.
   */
  plotHeight?: string;
  /**
   * The family's chunk of library modules, when it is not in the first one
   * (`ChartChunk`): the frame and its name stand while it loads, as they do
   * for the library itself.
   */
  chunk?: ChartChunk;
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
  onBrush,
  legendEntries,
  chunk,
  plotHeight,
}: EChartProps) {
  const messages = useViewMessages();
  const sentence = useContext(ChartSentence);
  const sentenceId = useId();
  const image = useContext(ChartImageTarget);
  const plot = useRef<HTMLDivElement>(null);
  const frame = useRef<HTMLDivElement>(null);
  const library = useChartLibrary(chunk);
  // The library threw creating or drawing: said in render, where the
  // boundary around the chart catches it (`ChartFailure`), rather than lost
  // in the size observer's callback, which no boundary sees.
  const [broken, setBroken] = useState<ChartFailure>();
  const mode = useSurfaceTheme();
  const tokens = useSurfaceTokens();
  // Paper reads the theme again: light, and patterned (`print.ts`).
  const printing = usePrinting();
  const [theme, setTheme] = useState<ChartTheme>();
  useLayoutEffect(() => {
    if (!plot.current) return;
    const next = readChartTheme(plot.current);
    setTheme(previous => (previous?.key === next.key ? previous : next));
  }, [mode, tokens, printing]);

  const chart = useRef<ECharts>(undefined);
  const menuOpen = useContext(ChartMenuOpen);
  // Whether a brush's cover stands on the drawing, waiting for its menu.
  const brushed = useRef(false);
  const menuShown = useRef(menuOpen);
  useEffect(() => {
    menuShown.current = menuOpen;
    if (menuOpen) chart.current?.dispatchAction({ type: 'hideTip' });
    // The menu over a brushed stretch has gone: so does the stretch.
    else if (brushed.current) clearBrush(chart.current, frame.current);
    if (!menuOpen) brushed.current = false;
  }, [menuOpen]);
  const size = useRef({ width: 0, height: 0 });
  // Patterns over the colours (decal, D33 Q57): the host's pin on the
  // theme, else the reader's system asking for more contrast.
  const systemPatterns = usePatterns();
  const patterned = theme?.patterns ?? systemPatterns;
  const zoom = useRef<{ for: unknown; window?: ZoomWindow }>({ for: zoomFor });
  const latest = useRef({
    option,
    adapt,
    onClick,
    onBrush,
    theme,
    patterned,
    zoomFor,
    legendEntries,
  });
  useLayoutEffect(() => {
    latest.current = {
      option,
      adapt,
      onClick,
      onBrush,
      theme,
      patterned,
      zoomFor,
      legendEntries,
    };
  });
  // The drawing as a picture takes it (D33 Q58): the option for the theme
  // in force, fitted to the picture's width as a resize would fit it, at
  // the whole range — whatever the reader zoomed to, the picture says the
  // range its head names.
  useLayoutEffect(() => {
    if (!image || !library) return;
    image.register(minWidth => {
      const now = latest.current;
      const { width: shown, height: tall } = size.current;
      if (!now.theme || !shown || !tall) return undefined;
      const width = Math.max(Math.round(shown), minWidth);
      const height = Math.round((tall * width) / shown);
      const theme = pictureTheme(now.theme);
      const drawing = composed(now.option(theme), now.patterned, undefined);
      const adjustment = now.adapt?.(width, height, undefined, theme.text);
      return {
        library,
        option: adjustment ? merged(drawing, adjustment) : drawing,
        width,
        height,
        theme,
        legend: (now.legendEntries ?? []).filter(entry => !entry.hidden),
      };
    });
    return () => image.register(null);
  }, [image, library]);
  // The pointer the last press came from, and where it let go since: a tap
  // is told from a click by it, and a brush's menu hangs there.
  const pointer = useRef<{
    type: string;
    at?: { clientX: number; clientY: number };
  }>({ type: 'mouse' });
  // The mark a first tap showed the tooltip of (`onClick`): the next tap on
  // it is the press.
  const armed = useRef<string | undefined>(undefined);
  // Whether the drawing carries a brush, and whether its drag is taken yet.
  // A whole new option resets the brush component, so the drag is taken
  // when a pointer goes down on the plot — before the library sees the
  // press — rather than after every drawing: taken there, it was one more
  // update per drawing, whose render came after the marks had landed and
  // said `finished` again, over the next drawing's (the 10 000-day story).
  const cursor = useRef({ brush: false, taken: false });
  useLayoutEffect(() => {
    const element = plot.current;
    if (!element) return;
    const down = (event: PointerEvent) => {
      pointer.current = { type: event.pointerType };
      if (event.pointerType === 'touch') return;
      disarm(armed, frame.current);
      if (cursor.current.brush && !cursor.current.taken && chart.current) {
        chart.current.dispatchAction(BRUSH_CURSOR);
        cursor.current.taken = true;
      }
    };
    // On the document: a brush may be let go of past the plot's edge.
    const up = (event: PointerEvent) => {
      pointer.current = {
        type: event.pointerType || pointer.current.type,
        at: { clientX: event.clientX, clientY: event.clientY },
      };
    };
    const owner = element.ownerDocument;
    element.addEventListener('pointerdown', down, { capture: true });
    owner.addEventListener('pointerup', up, { capture: true });
    return () => {
      element.removeEventListener('pointerdown', down, { capture: true });
      owner.removeEventListener('pointerup', up, { capture: true });
    };
  }, []);

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
  const hugWidth = hugged ? Math.round(tall * (hug ?? 1)) : undefined;
  const legendNode =
    legend && placed
      ? typeof legend.node === 'function'
        ? legend.node(placed)
        : legend.node
      : undefined;

  // Created as soon as the plot has a size, drawn once every chart sized in
  // the same frame is created (`watchSize`).
  useLayoutEffect(() => {
    const element = plot.current;
    if (!library || !element) return;
    const failed = (error: unknown) =>
      setBroken(new ChartFailure('draw', error));
    const unwatch = watchSize(element, failed, (w, h) => {
      if (!w || !h) return undefined;
      size.current = { width: w, height: h };
      const shown = chart.current;
      if (shown)
        return () => {
          shown.resize({ width: w, height: h });
          draw(
            shown,
            undefined,
            fitTo(latest.current, w, h, zoom.current.window),
          );
        };
      const created = library.init(element, null, {
        renderer: 'svg',
        width: w,
        height: h,
      });
      created.on('click', params => {
        const handler = latest.current.onClick;
        if (!handler) return;
        const click = pointed(params as unknown as ChartClick, element);
        // A first tap on a mark is for reading it: its tooltip stays, and
        // says to tap again. Only the same mark tapped again is the press.
        if (pointer.current.type === 'touch') {
          const mark = `${click.seriesIndex ?? ''}:${click.dataIndex}`;
          if (armed.current !== mark) {
            armed.current = mark;
            frame.current?.setAttribute('data-tap-armed', 'true');
            return;
          }
        }
        disarm(armed, frame.current);
        // A press opens the follow-up menu at the point pressed, and the
        // tooltip standing there sat on top of it, over its first items.
        // The menu is the answer to the press; the tooltip steps aside.
        created.dispatchAction({ type: 'hideTip' });
        handler(click);
      });
      created.on('brushEnd', params => {
        const handler = latest.current.onBrush;
        if (!handler) return;
        brushed.current = true;
        frame.current?.setAttribute('data-brushed', 'true');
        created.dispatchAction({ type: 'hideTip' });
        handler(params, pointer.current.at ?? coverEnd(params, element));
        // A brush that opened no menu — nothing to ask about the stretch —
        // leaves no cover behind once the page has had its turn to open one.
        setTimeout(() => {
          if (!menuShown.current && brushed.current) {
            brushed.current = false;
            clearBrush(created, frame.current);
          }
        }, 300);
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
          const adjustment = fitTo(latest.current, width, height, window);
          if (adjustment && !created.isDisposed())
            created.setOption(adjustment);
        }, 16);
      });
      chart.current = created;
      return () => {
        const now = latest.current;
        if (!now.theme) return;
        const drawing = composed(
          now.option(now.theme),
          now.patterned,
          undefined,
        );
        cursor.current = { brush: Boolean(drawing.brush), taken: false };
        draw(created, drawing, fitTo(now, w, h));
      };
    });
    return () => {
      unwatch();
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
      try {
        const drawing = composed(option(theme), patterned, window);
        cursor.current = { brush: Boolean(drawing.brush), taken: false };
        draw(
          chart.current,
          drawing,
          adapt?.(size.current.width, size.current.height, window, theme.text),
        );
      } catch (error) {
        // Thrown from an effect, it reaches the boundary as it is; wrapped,
        // the boundary knows it for a chart's.
        throw new ChartFailure('draw', error);
      }
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
      aria-describedby={sentence ? sentenceId : undefined}
      className="relative min-h-0 min-w-0 flex-1"
      style={{ maxWidth: hugWidth, height: plotHeight }}
    >
      {/* Positioned inline: the library makes its element `relative` unless
          it already computes as positioned, and a stylesheet that has not
          arrived yet would lose it the plot's size. */}
      <div ref={plot} style={{ position: 'absolute', inset: 0 }} />
      {/* Read as the drawing's description, never seen: inside the image,
          so the frame's own children stay the legend and the plot. */}
      {sentence && (
        <span id={sentenceId} data-slot="chart-sentence" className="sr-only">
          {sentence}
        </span>
      )}
    </div>
  );
  if (broken) throw broken;
  return (
    <div
      ref={frame}
      data-slot="chart"
      data-legend={placed ?? 'none'}
      data-menu-open={menuOpen || undefined}
      data-patterns={patterned ? 'on' : 'off'}
      {...data}
      // What a first tap's tooltip adds under its rows: tap again to follow
      // up. A string the stylesheet writes after the tooltip's own content
      // (`data-tap-armed`), so no family's tooltip has to know of taps.
      style={
        onClick
          ? ({
              '--_fve-tap-hint': cssString(
                messages.label('label.drill.tap-again'),
              ),
            } as CSSProperties)
          : undefined
      }
      className={cn(
        'flex aspect-video min-h-52 w-full gap-2 text-xs',
        // The tooltip the library draws is ours (`tooltipHtml`), inside its
        // own transparent box: hidden, nothing of it is on screen.
        'data-menu-open:[&_[data-slot=chart-tooltip]]:invisible',
        'data-tap-armed:[&_[data-slot=chart-tooltip]]:after:text-muted-foreground data-tap-armed:[&_[data-slot=chart-tooltip]]:after:content-(--_fve-tap-hint)',
        placed === 'right' ? 'flex-row' : 'flex-col',
        hugged && 'justify-center',
        plotHeight && 'aspect-auto min-h-0 *:data-[slot=chart-plot]:flex-none',
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
 * A press with the point it happened at on the page. A tap's native event is
 * a touch, which carries its point in `changedTouches` rather than on
 * itself, so a menu hung from its `clientX` stood in the page's corner; the
 * library's own event says where on the drawing it was (`offsetX`,
 * `offsetY`), which is read back onto the page.
 */
function pointed(click: ChartClick, element: HTMLElement): ChartClick {
  const native = click.event?.event;
  if (native && Number.isFinite(native.clientX)) return click;
  const at = click.event as { offsetX?: number; offsetY?: number } | undefined;
  const box = element.getBoundingClientRect();
  return {
    ...click,
    event: {
      ...click.event,
      event: {
        clientX: box.left + (at?.offsetX ?? box.width / 2),
        clientY: box.top + (at?.offsetY ?? box.height / 2),
      },
    },
  };
}

/**
 * Where a brush's menu hangs when no pointer said where it let go (a drag
 * the page made itself): the cover's far edge, halfway down the plot.
 */
function coverEnd(
  said: unknown,
  element: HTMLElement,
): { clientX: number; clientY: number } {
  const box = element.getBoundingClientRect();
  const areas = (said as { areas?: { range?: unknown }[] }).areas;
  const range = areas?.[0]?.range;
  const edge = Array.isArray(range)
    ? Math.max(...range.filter((x): x is number => typeof x === 'number'))
    : box.width / 2;
  return {
    clientX: box.left + (Number.isFinite(edge) ? edge : box.width / 2),
    clientY: box.top + box.height / 2,
  };
}

/** The brush's cover taken off the drawing, and the frame told so. */
function clearBrush(chart: ECharts | undefined, frame: HTMLElement | null) {
  if (chart && !chart.isDisposed()) chart.dispatchAction(BRUSH_CLEAR);
  frame?.removeAttribute('data-brushed');
}

/** No mark waits for a second tap any more. */
function disarm(
  armed: { current: string | undefined },
  frame: HTMLElement | null,
) {
  armed.current = undefined;
  frame?.removeAttribute('data-tap-armed');
}

/** Text as a CSS string, for `content` to write. */
function cssString(text: string): string {
  return `"${text.replace(/["\\]/g, '\\$&').replace(/\n/g, ' ')}"`;
}

/**
 * The library, once its chunk has arrived. A failed load is thrown in
 * render, so the chart's `RenderBoundary` says why and offers to try again.
 */
function useChartLibrary(chunk?: ChartChunk) {
  const [library, setLibrary] = useState(() => loadedCharts(chunk));
  const [failure, setFailure] = useState<{ error: unknown }>();
  useEffect(() => {
    if (library) return;
    let live = true;
    loadCharts(chunk).then(
      loaded => live && setLibrary(() => loaded),
      (error: unknown) => live && setFailure({ error }),
    );
    return () => {
      live = false;
    };
  }, [library, chunk]);
  if (failure) throw new ChartFailure('load', failure.error);
  return library;
}
