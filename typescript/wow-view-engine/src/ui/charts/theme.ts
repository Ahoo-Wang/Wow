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

import {
  formatRgb,
  interpolate,
  modeHsl,
  modeLab,
  modeLch,
  modeOklab,
  modeOklch,
  modeP3,
  modeRgb,
  parse,
  // Registers a colour space with the parser; not a React hook.
  useMode as registerMode,
} from 'culori/fn';
import { CHART_COLOR_SLOTS } from '../../model/index.js';

registerMode(modeRgb);
registerMode(modeHsl);
registerMode(modeLab);
registerMode(modeLch);
registerMode(modeOklab);
registerMode(modeOklch);
registerMode(modeP3);

/**
 * The theme a chart is drawn in, as concrete colours.
 *
 * The stylesheet stays the one source of truth (`--chart-1..8`,
 * `--foreground`, `--muted-foreground`, `--border`, and whatever a host
 * sets through `--fve-*`); this is that cascade read back off the chart's
 * own element. The option handed to the library never holds a `var()`:
 * the library derives a hovered mark's colour and a label's contrast by
 * parsing the colour it was given, and a custom property parses as nothing
 * (echarts#16044, #19743). Nor an `oklch()`, which the theme is written in
 * and the library's parser does not read — so every colour is converted to
 * `rgb()` here.
 */
export interface ChartTheme {
  /** The eight slots, in the order the stylesheet validated them in. */
  palette: readonly string[];
  /** Text written over the plot: a value label, a total. */
  foreground: string;
  /** Ticks, axis titles and the grey of a pie's "Other". */
  muted: string;
  /** Gridlines and axis rules. */
  border: string;
  /** What the chart stands on, drawn around a value label as its halo. */
  ground: string;
  fontFamily: string;
  /** A colour the spec pinned — a `var(--slot)` or any CSS colour — made concrete. */
  resolve(color: string): string;
  /** Equal for two readings of the same theme. */
  key: string;
}

/**
 * The light theme's values, for where nothing can be read: jsdom resolves no
 * custom property, and a chart outside any stylesheet still has to draw in
 * something. They are the stylesheet's light tokens, converted.
 */
const FALLBACK = {
  palette: [
    'rgb(42, 120, 214)',
    'rgb(235, 104, 52)',
    'rgb(27, 175, 122)',
    'rgb(237, 161, 0)',
    'rgb(232, 123, 164)',
    'rgb(0, 131, 0)',
    'rgb(74, 58, 167)',
    'rgb(227, 73, 72)',
  ],
  foreground: 'rgb(10, 10, 10)',
  muted: 'rgb(115, 115, 115)',
  border: 'rgb(229, 229, 229)',
  ground: 'rgb(255, 255, 255)',
  fontFamily: 'sans-serif',
};

/** A theme slot as the palette names it. */
const VARIABLE = /^var\((--[\w-]+)\)$/;

/** Any CSS colour as `rgb()`/`rgba()`; `undefined` when it is none. */
export function concreteColor(text: string | undefined): string | undefined {
  const value = text?.trim();
  if (!value) return undefined;
  const color = parse(value);
  return color && formatRgb(color);
}

/**
 * `color` at `strength` over `ground`, as the one opaque colour the eye gets
 * there: what painting it at that opacity on that ground shows, worked out
 * before the library sees it. A scale drawn apart from the marks — a
 * heatmap's colour bar — then reads the same colours the marks show, on
 * whatever the ground is; handed the opacity instead, the library drew the
 * bar's pale end against a ground of its own, bright on a dark page where
 * the cells were near black (audit P1-9). Mixed in `rgb`, as the opacity
 * composites. A colour that does not parse is given back as it came.
 */
export function mixColor(
  ground: string,
  color: string,
  strength: number,
): string {
  const under = parse(ground);
  const over = parse(color);
  if (!under || !over) return color;
  return formatRgb(interpolate([under, over], 'rgb')(strength));
}

export function readChartTheme(element: Element): ChartTheme {
  const style = getComputedStyle(element);
  const token = (name: string) => concreteColor(style.getPropertyValue(name));
  const palette = Array.from(
    { length: CHART_COLOR_SLOTS },
    (_, index) => token(`--chart-${index + 1}`) ?? FALLBACK.palette[index],
  );
  const read = {
    palette,
    foreground: token('--foreground') ?? FALLBACK.foreground,
    muted: token('--muted-foreground') ?? FALLBACK.muted,
    border: token('--border') ?? FALLBACK.border,
    ground: groundOf(element) ?? FALLBACK.ground,
    fontFamily: style.fontFamily || FALLBACK.fontFamily,
  };
  const resolved = new Map<string, string>();
  return {
    ...read,
    key: JSON.stringify(read),
    resolve(color) {
      let found = resolved.get(color);
      if (found === undefined) {
        const variable = VARIABLE.exec(color.trim())?.[1];
        const slot = /^--chart-(\d+)$/.exec(variable ?? '')?.[1];
        found =
          (slot && palette[(Number(slot) - 1) % palette.length]) ||
          (variable ? token(variable) : concreteColor(color)) ||
          palette[0];
        resolved.set(color, found);
      }
      return found;
    },
  };
}

/**
 * The paint under the chart: the first ancestor that has any. A value
 * label is drawn with a halo of it, so a label crossing a gridline or the
 * top of a neighbouring bar stays legible — the halo has to be the ground
 * the label stands on, which is the card in a panel and the page in a
 * workbench.
 */
function groundOf(element: Element): string | undefined {
  for (let node: Element | null = element; node; node = node.parentElement) {
    const color = concreteColor(getComputedStyle(node).backgroundColor);
    if (color && !/^rgba\(.*,\s*0\)$/.test(color)) return color;
  }
  return undefined;
}
