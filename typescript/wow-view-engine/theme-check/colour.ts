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

/**
 * The colour arithmetic the theme's resolver measures with
 * (ui/theme.md「质量门」): a colour as clipped sRGB with alpha, a relative
 * `oklch(from …)` worked out, one colour composited over another the way
 * the browser paints it, and the WCAG contrast of an ink on a ground. Pure
 * functions over colour values: nothing here knows a layer, a preset or
 * the registry, which are `resolve.ts`'s.
 */
import { type Color, converter, parse, wcagContrast } from 'culori';

const toRgb = converter('rgb');
const toOklch = converter('oklch');

/** One opaque or half-transparent sRGB colour, channels clipped to 0–1. */
export interface Rgba {
  r: number;
  g: number;
  b: number;
  alpha: number;
}

/**
 * How an `oklch()` outside sRGB reaches the screen: clipped channel by
 * channel, or carried back along chroma at its own lightness (CSS Color 4's
 * gamut mapping). Browsers differ, so the brand sweep holds both.
 */
export type Gamut = 'clip' | 'chroma';

/** A colour as sRGB with alpha, channels clipped to the gamut. */
export function rgba(color: Color): Rgba {
  const rgb = toRgb(color);
  const clip = (channel: number) => Math.min(1, Math.max(0, channel));
  return {
    r: clip(rgb.r),
    g: clip(rgb.g),
    b: clip(rgb.b),
    alpha: rgb.alpha ?? 1,
  };
}

/** Splits text at the spaces that are not inside brackets. */
function splitWords(text: string): string[] {
  const words: string[] = [];
  let depth = 0;
  let start = 0;
  for (let at = 0; at <= text.length; at += 1) {
    const char = text[at];
    if (char === '(') depth += 1;
    else if (char === ')') depth -= 1;
    else if ((char === ' ' || char === undefined) && depth === 0) {
      if (at > start) words.push(text.slice(start, at));
      start = at + 1;
    }
  }
  return words;
}

/** Splits a function's arguments at the commas that are not nested. */
export function splitArguments(text: string): string[] {
  const parts: string[] = [];
  let depth = 0;
  let start = 0;
  for (let at = 0; at < text.length; at += 1) {
    const char = text[at];
    if (char === '(') depth += 1;
    else if (char === ')') depth -= 1;
    else if (char === ',' && depth === 0) {
      parts.push(text.slice(start, at).trim());
      start = at + 1;
    }
  }
  parts.push(text.slice(start).trim());
  return parts;
}

/**
 * `oklch(from <colour> <l> <c> <h>)`, CSS Color 5's relative colour, as far
 * as the stylesheet writes it: each channel a number, the origin's own
 * channel (`l`, `c`, `h`), or `clamp()` / `min()` / `max()` of those.
 */
export function relativeOklch(value: string): Color | undefined {
  const match = /^oklch\(from\s+([\s\S]+)\)$/.exec(value);
  if (!match) return undefined;
  const words = splitWords(match[1]);
  const [l, c, h] = words.slice(-3);
  const origin = parse(words.slice(0, -3).join(' '));
  if (!origin) throw new Error(`unreadable origin in ${value}`);
  const from = toOklch(origin);
  const channels: Record<string, number> = {
    l: from.l,
    c: from.c ?? 0,
    h: from.h ?? 0,
  };
  const channel = (expression: string): number => {
    const call = /^(clamp|min|max)\(([\s\S]+)\)$/.exec(expression);
    if (call) {
      const values = splitArguments(call[2]).map(channel);
      if (call[1] === 'min') return Math.min(...values);
      if (call[1] === 'max') return Math.max(...values);
      const [low, x, high] = values;
      return Math.max(low, Math.min(x, high));
    }
    if (expression in channels) return channels[expression];
    const number = Number.parseFloat(expression);
    if (Number.isNaN(number)) throw new Error(`unreadable ${expression}`);
    return number;
  };
  return { mode: 'oklch', l: channel(l), c: channel(c), h: channel(h) };
}

/** `top` over `bottom`, as the browser composites it: in gamma-encoded sRGB. */
export function over(top: Rgba, bottom: Rgba): Rgba {
  const mix = (a: number, b: number) => a * top.alpha + b * (1 - top.alpha);
  return {
    r: mix(top.r, bottom.r),
    g: mix(top.g, bottom.g),
    b: mix(top.b, bottom.b),
    alpha: 1,
  };
}

/** A token at a Tailwind opacity modifier (`bg-input/30`). */
export function at(color: Rgba, opacity: number): Rgba {
  return { ...color, alpha: color.alpha * opacity };
}

/** WCAG 2.x contrast between a colour and the opaque ground under it. */
export function contrast(ink: Rgba, ground: Rgba): number {
  if (ground.alpha !== 1) throw new Error('a ground must be opaque');
  const drawn = over(ink, ground);
  return wcagContrast({ mode: 'rgb', ...drawn }, { mode: 'rgb', ...ground });
}
