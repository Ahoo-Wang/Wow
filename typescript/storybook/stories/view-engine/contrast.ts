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

/** What one element's own fill measured, and against what. */
export interface FillContrast {
  /** The ratio an assertion should read. */
  ratio: number;
  /** The two colours as CSS, so a failure says what it saw. */
  colors: { fill: string; surface: string };
}

/**
 * One element's own fill, measured against the surface it is painted on.
 *
 * A `Separator` is a line drawn as a *background* rather than as a border,
 * so `measureBorderContrast` has nothing to read on it — and a divider that
 * cannot be told from the page it is on is not a divider. The fill is
 * composed the way the browser paints it, so a half-transparent token is
 * measured as what reaches the eye.
 */
export function measureFillContrast(element: Element): FillContrast {
  const surface = surfaceUnder(element.parentElement);
  const fill = paintedSurface(element);
  return {
    ratio: contrastRatio(fill, surface),
    colors: { fill: css(fill), surface: css(surface) },
  };
}

/** What two stacked surfaces came to, and what the lower one was dimmed by. */
export interface LayerSeparation {
  /**
   * The better of the two below — what tells the layers apart at all. A
   * light theme separates them by the scrim, a dark one by the edge: near
   * black there is nothing left for a scrim to darken.
   */
  ratio: number;
  /** The upper surface's own fill against the dimmed one behind it. */
  onFill: number;
  /** The upper surface's ring against that same dimmed one. */
  onRing: number;
  /** The three composited colours as CSS, so a failure says what it saw. */
  colors: { front: string; ring: string; behind: string };
}

/**
 * How far a popup stands off the surface it was opened from.
 *
 * A dialog raised from inside another dialog is two cards of the same
 * `bg-popover`, so on its own the upper one is invisible against the lower —
 * 1.00:1, a white card dropped into a white card. What separates them is the
 * backdrop the upper one brings, and a backdrop is a *half-transparent* layer
 * over a surface it is not an ancestor of: `getComputedStyle` on the covered
 * card still reports its own colour, so the composition has to be done here.
 */
export function measureLayerSeparation(
  front: Element,
  backdrop: Element,
  behind: Element,
): LayerSeparation {
  const above = paintedSurface(front);
  const dimmed = composite(
    layer(getComputedStyle(backdrop).backgroundColor),
    paintedSurface(behind),
  );
  // The ring is drawn outside the popup's own box, so it lands on the dimmed
  // surface rather than on the card it belongs to.
  const ring = composite(ringLayer(getComputedStyle(front).boxShadow), dimmed);
  const onFill = contrastRatio(above, dimmed);
  const onRing = contrastRatio(ring, dimmed);
  return {
    ratio: Math.max(onFill, onRing),
    onFill,
    onRing,
    colors: { front: css(above), ring: css(ring), behind: css(dimmed) },
  };
}

/** What one edge measured, and against what. */
export interface EdgeContrast {
  ratio: number;
  /** The two colours as CSS, so a failure says what it saw. */
  colors: { edge: string; surface: string };
}

/**
 * The edge a registry card draws — a `ring`, which is a `box-shadow`, not a
 * border — measured against what is behind it: the ring lies outside the
 * card's own box, so on whatever the card is standing on.
 */
export function measureRingContrast(element: Element): EdgeContrast {
  const surface = surfaceUnder(element.parentElement);
  const ring = composite(
    ringLayer(getComputedStyle(element).boxShadow),
    surface,
  );
  return {
    ratio: contrastRatio(ring, surface),
    colors: { edge: css(ring), surface: css(surface) },
  };
}

/**
 * An element's outline — a focus mark drawn inside its edge — measured
 * against the element's own painted fill, which is what it lies on.
 */
export function measureOutlineContrast(element: Element): EdgeContrast {
  const style = getComputedStyle(element);
  if (style.outlineStyle === 'none' || parseFloat(style.outlineWidth) === 0)
    throw new Error('The element draws no outline to measure.');
  const fill = paintedSurface(element);
  const outline = composite(layer(style.outlineColor), fill);
  return {
    ratio: contrastRatio(outline, fill),
    colors: { edge: css(outline), surface: css(fill) },
  };
}

/**
 * The mark an element wears while the keyboard is on it, measured against
 * both colours it lies between: the element's own painted fill and the
 * surface outside it (for a table cell, its row's ground — what the rows
 * around it are painted in).
 *
 * The mark is the outline where one is drawn, and otherwise the first
 * painted `box-shadow` — a ring, or a row's inset edge — laid over the fill
 * when it is inset (or an outline pulled inside the edge) and over the
 * surface when it is not. The half-strength halo beside it is emphasis, not
 * the indicator, as `styles.css` says of `--ring`, so it is what WCAG 1.4.11
 * asks 3:1 of that is measured here.
 */
export function measureFocusMark(element: Element): BorderContrast {
  const style = getComputedStyle(element);
  const fill = paintedSurface(element);
  const surface = surfaceUnder(element.parentElement);
  let mark: Opaque;
  let over: Opaque;
  if (style.outlineStyle !== 'none' && parseFloat(style.outlineWidth) > 0) {
    over = parseFloat(style.outlineOffset) < 0 ? fill : surface;
    mark = composite(layer(style.outlineColor), over);
  } else {
    const shadows = style.boxShadow.split(/,(?![^(]*\))/);
    const drawn = shadows.find(shadow => ringLayer(shadow).alpha > 0);
    if (!drawn) throw new Error('The element draws no focus mark to measure.');
    over = /\binset\b/.test(drawn) ? fill : surface;
    mark = composite(ringLayer(drawn), over);
  }
  const onFill = contrastRatio(mark, fill);
  const onSurface = contrastRatio(mark, surface);
  return {
    ratio: Math.min(onFill, onSurface),
    onFill,
    onSurface,
    colors: { border: css(mark), fill: css(fill), surface: css(surface) },
  };
}

/**
 * A chart's mark — an SVG shape the library drew — measured against the
 * surface its drawing stands on: its `fill` at the opacity it was drawn
 * with (`fill-opacity` and `opacity` both), composed the way the browser
 * paints it. What a faded group comes to on the card (U-15).
 */
export function measureMarkContrast(mark: SVGElement): FillContrast {
  const surface = surfaceUnder(mark.closest('svg')?.parentElement ?? null);
  const drawn = layer(mark.getAttribute('fill') ?? 'transparent');
  const opacity = ['fill-opacity', 'opacity'].reduce(
    (alpha, name) => alpha * Number(mark.getAttribute(name) ?? 1),
    1,
  );
  const fill = composite({ ...drawn, alpha: drawn.alpha * opacity }, surface);
  return {
    ratio: contrastRatio(fill, surface),
    colors: { fill: css(fill), surface: css(surface) },
  };
}

/**
 * The colour a computed `box-shadow` actually paints, as a layer.
 *
 * Tailwind v4 writes `box-shadow` as five slots — inset shadow, inset ring,
 * ring offset, ring, shadow — and the ones nothing filled are still there as
 * `rgba(0, 0, 0, 0)`. So this is the first colour with any opacity to it
 * rather than simply the first, and an element with no shadow at all comes
 * back fully transparent: composited, that is the surface behind it, which
 * is exactly what "no ring" should measure as.
 */
function ringLayer(boxShadow: string): Layer {
  const colors = boxShadow.match(/[a-z-]+\([^)]*\)|#[0-9a-f]{3,8}/gi) ?? [];
  for (const color of colors) {
    const painted = layer(color);
    if (painted.alpha > 0) return painted;
  }
  return { r: 0, g: 0, b: 0, alpha: 0 };
}

/** One element's own background over whatever it is painted on. */
function paintedSurface(element: Element): Opaque {
  return composite(
    layer(getComputedStyle(element).backgroundColor),
    surfaceUnder(element.parentElement),
  );
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
