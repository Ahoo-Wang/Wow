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
 * What a control's edge measures against what is behind it.
 *
 * The theme writes its colours as `oklch()` and Tailwind's opacity modifiers
 * as `color-mix()`, so what `getComputedStyle` hands back is whatever space
 * the browser kept — `oklch(…)`, `oklab(…)`, `color(srgb …)` or a legacy
 * `rgba(…)`. Parsing is culori's job; the rest is the WCAG arithmetic, which
 * is short enough to keep in one place: alpha composition the way the browser
 * paints it (in gamma-encoded sRGB), relative luminance, and the ratio.
 */
import { converter, parse } from 'culori';

const toRgb = converter('rgb');

/** One opaque sRGB colour, channels in 0–1. */
interface Opaque {
  r: number;
  g: number;
  b: number;
}

interface Layer extends Opaque {
  alpha: number;
}

/** What one control's border measured, and against what. */
export interface BorderContrast {
  /** The lower of the two ratios below — what an assertion should read. */
  ratio: number;
  /** The border against the control's own fill, where it has one. */
  onFill: number;
  /** The border against the surface the control sits on. */
  onSurface: number;
  /** The three colours as CSS, so a failure says what it saw. */
  colors: { border: string; fill: string; surface: string };
}

/** What one element's text measured, and against what. */
export interface TextContrast {
  /** The ratio a 1.4.3 assertion should read. */
  ratio: number;
  /** The two colours as CSS, so a failure says what it saw. */
  colors: { text: string; background: string };
}

/**
 * The text of one element, measured against what is painted behind it.
 *
 * A callout carries its tone in its text colour rather than in a fill, so
 * this is the number that decides whether it is readable — and it is the one
 * `styles.css` corrects for: `--warning` on a white card and the same token
 * on a dark one are two different problems. The background is composed the
 * way the browser paints it, the element's own (possibly transparent) fill
 * included, so a callout with no background of its own is measured against
 * the card under it.
 */
export function measureTextContrast(element: Element): TextContrast {
  const style = getComputedStyle(element);
  const background = composite(
    layer(style.backgroundColor),
    surfaceUnder(element.parentElement),
  );
  const text = composite(layer(style.color), background);
  return {
    ratio: contrastRatio(text, background),
    colors: { text: css(text), background: css(background) },
  };
}

/**
 * The border of one control, measured against what is behind it.
 *
 * "Behind it" is two things and both have to clear the bar: the surface the
 * control sits on, and the control's own fill when it has one — the dark
 * theme gives inputs a `bg-input/30` wash, which is the same token at another
 * opacity and therefore moves with it.
 */
export function measureBorderContrast(
  element: Element,
  side: 'top' | 'bottom' = 'top',
): BorderContrast {
  const style = getComputedStyle(element);
  const width = side === 'top' ? style.borderTopWidth : style.borderBottomWidth;
  if (parseFloat(width) === 0)
    throw new Error('The element draws no border to measure.');

  const surface = surfaceUnder(element.parentElement);
  const fill = composite(layer(style.backgroundColor), surface);
  const border = composite(
    layer(side === 'top' ? style.borderTopColor : style.borderBottomColor),
    fill,
  );
  const onFill = contrastRatio(border, fill);
  const onSurface = contrastRatio(border, surface);
  return {
    ratio: Math.min(onFill, onSurface),
    onFill,
    onSurface,
    colors: {
      border: css(border),
      fill: css(fill),
      surface: css(surface),
    },
  };
}

/**
 * The first colour an element is painted over: the nearest ancestor whose
 * background is opaque, with every half-transparent background between them
 * laid over it in the order the browser paints them.
 */
function surfaceUnder(from: Element | null): Opaque {
  const layers: Layer[] = [];
  for (let node = from; node; node = node.parentElement) {
    const painted = layer(getComputedStyle(node).backgroundColor);
    if (painted.alpha === 0) continue;
    layers.push(painted);
    if (painted.alpha === 1) break;
  }
  // The page under everything, for a chain that never reached an opaque one.
  let base: Opaque = { r: 1, g: 1, b: 1 };
  for (let index = layers.length - 1; index >= 0; index -= 1)
    base = composite(layers[index], base);
  return base;
}

function layer(color: string): Layer {
  const parsed = parse(color);
  if (!parsed) throw new Error(`Unreadable colour: ${color}`);
  const rgb = toRgb(parsed);
  return {
    r: clamp(rgb.r),
    g: clamp(rgb.g),
    b: clamp(rgb.b),
    alpha: rgb.alpha ?? 1,
  };
}

/** `top` over `bottom`, as the browser composites it: in gamma-encoded sRGB. */
function composite(top: Layer, bottom: Opaque): Opaque {
  const mix = (over: number, under: number) =>
    over * top.alpha + under * (1 - top.alpha);
  return {
    r: mix(top.r, bottom.r),
    g: mix(top.g, bottom.g),
    b: mix(top.b, bottom.b),
  };
}

/** WCAG 2.x contrast, `(lighter + 0.05) / (darker + 0.05)`. */
export function contrastRatio(one: Opaque, other: Opaque): number {
  const [light, dark] = [luminance(one), luminance(other)].sort(
    (a, b) => b - a,
  );
  return (light + 0.05) / (dark + 0.05);
}

/** WCAG relative luminance. */
function luminance({ r, g, b }: Opaque): number {
  return 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b);
}

function linear(channel: number): number {
  return channel <= 0.03928
    ? channel / 12.92
    : ((channel + 0.055) / 1.055) ** 2.4;
}

function clamp(channel: number): number {
  return Math.min(1, Math.max(0, channel));
}

function css({ r, g, b }: Opaque): string {
  const byte = (channel: number) => Math.round(channel * 255);
  return `rgb(${byte(r)}, ${byte(g)}, ${byte(b)})`;
}
