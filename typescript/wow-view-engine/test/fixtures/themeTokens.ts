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
 * The theme's tokens as a preset and a mode resolve them, read off the
 * shipped `styles.css` and `themes.css` — no browser, no cascade engine,
 * just the rules those files keep (theme-architecture.md 3, S2): every token
 * is `var(--fve-[dark-]<name>, var(--fvp-[dark-]<name>, <built-in>))` — the
 * host's layer, then the preset's — a preset assigns the `--fvp-*` it
 * changes, and the reset rule (`@layer fve-reset`) empties the preset layer
 * on every element that names a preset before its block applies.
 *
 * Tokens are keyed by the variable the blocks declare — `--primary`, and
 * the engine's own `--_fve-row-hover`; `tokenVariable` names it for a
 * registry entry.
 *
 * The WCAG arithmetic is the one `stories/view-engine/contrast.ts` and the
 * chart's `inkOn` use: a half-transparent layer composed over what it lies
 * on in gamma-encoded sRGB, the way the browser paints it, channels clipped
 * to the gamut, then relative luminance and `(lighter + 0.05) / (darker +
 * 0.05)` — culori's `wcagContrast`.
 */

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import {
  clampChroma,
  type Color,
  converter,
  parse,
  wcagContrast,
} from 'culori';
import postcss from 'postcss';
import { themesSource } from '../../scripts/themes.mjs';
import {
  declaredVariable,
  type TokenEntry,
  TOKENS,
} from '../../src/ui/theme/tokens';

/**
 * The variable the blocks declare a registry token as: `--<name>`, or the
 * engine's own `--_fve-<name>`; `--<name>` for a name the registry lacks.
 */
export function tokenVariable(name: string): string {
  const entry = (TOKENS as readonly TokenEntry[]).find(
    token => token.name === name,
  );
  return (entry && declaredVariable(entry)) ?? `--${name}`;
}

/**
 * The variables the blocks declare a colour as — the registry's colour
 * tokens, and the engine's own derived ones every token reads through (the
 * change convention's pair).
 */
const COLOR_VARIABLES: ReadonlySet<string> = new Set([
  ...(TOKENS as readonly TokenEntry[])
    .filter(entry => entry.kind === 'color')
    .map(entry => declaredVariable(entry))
    .filter((variable): variable is string => variable !== undefined),
  '--_fve-convention-rise',
  '--_fve-convention-fall',
]);

const parsed = new Map<string, postcss.Root>();

/** A stylesheet of `src/`, parsed once: the brand sweep reads it a thousand times. */
const source = (file: string): postcss.Root => {
  let root = parsed.get(file);
  if (!root) {
    root = postcss.parse(
      readFileSync(join(import.meta.dirname, '..', '..', 'src', file), 'utf8'),
    );
    parsed.set(file, root);
  }
  return root;
};

/**
 * The host's `--fve-*`, as a host would put them on `<html>`: read first by
 * every token, and read by a preset's own values too — `brand` derives its
 * colours from `--fve-brand` (themes.md 2.7).
 */
export type HostVariables = Readonly<Record<string, string>>;

/** The brand colour the matrix measures `brand` with; the sweep tries all. */
export const BRAND_SAMPLE = '#7c3aed';

const DEFAULT_HOST: HostVariables = { '--fve-brand': BRAND_SAMPLE };

export type Mode = 'light' | 'dark';

/** The host's change convention, `data-fve-change-colors` (themes.md 2.6). */
export type Convention = 'semantic' | 'green-up' | 'red-up';

export const CONVENTIONS: readonly Convention[] = [
  'semantic',
  'green-up',
  'red-up',
];

/** One opaque or half-transparent sRGB colour, channels clipped to 0–1. */
export interface Rgba {
  r: number;
  g: number;
  b: number;
  alpha: number;
}

const toRgb = converter('rgb');

/** A declaration's value on one line, with no padding inside its brackets. */
const tidy = (value: string) =>
  value
    .replace(/\s+/g, ' ')
    .replace(/\(\s+/g, '(')
    .replace(/\s+\)/g, ')')
    .trim();
const toOklab = converter('oklab');

const LIGHT_BLOCK = '.fve-root,\n.fve-tokens';
const DARK_BLOCK =
  ".dark .fve-root:not([data-theme='light']),\n.fve-root[data-theme='dark'],\n.dark .fve-tokens";

/** A selector list compared by its parts, whatever the indentation. */
const sameSelector = (one: string, other: string) =>
  one.replace(/\s+/g, ' ') === other.replace(/\s+/g, ' ');

/**
 * Every custom property one rule of `styles.css` declares, as written — on
 * a screen: what the print rules put over it on paper is not a token block.
 */
function block(selector: string): Map<string, string> {
  const declared = new Map<string, string>();
  source('styles.css').walkRules(rule => {
    if (!sameSelector(rule.selector, selector)) return;
    if (rule.parent?.type === 'atrule' && /^print$/.test(rule.parent.params))
      return;
    rule.walkDecls(/^--/, decl => {
      declared.set(decl.prop, tidy(decl.value));
    });
  });
  if (declared.size === 0) throw new Error(`no ${selector} in styles.css`);
  return declared;
}

/**
 * The change convention's pair, as `styles.css` sets it on the boundary: the
 * default rule, and the one `red-up` crosses it with. `green-up` colours a
 * direction as `semantic` does, so it adds no rule of its own.
 */
function conventionBlock(convention: Convention): Map<string, string> {
  const declared = new Map<string, string>();
  source('styles.css').walkRules(rule => {
    const crossed = rule.selector.includes("data-fve-change-colors='red-up'");
    if (
      !rule.some(
        node => node.type === 'decl' && node.prop === '--_fve-convention-rise',
      )
    )
      return;
    if (crossed && convention !== 'red-up') return;
    rule.walkDecls(/^--/, decl => {
      declared.set(decl.prop, tidy(decl.value));
    });
  });
  return declared;
}

let presetCache: Map<string, Map<string, string>> | undefined;

/** Every `:where([data-fve-preset='<name>'])` block of a stylesheet. */
export function presetBlocks(css: string): Map<string, Map<string, string>> {
  const found = new Map<string, Map<string, string>>();
  postcss.parse(css).walkRules(rule => {
    const name = /data-fve-preset='([^']+)'/.exec(rule.selector)?.[1];
    if (!name) return;
    const assigned = new Map<string, string>();
    rule.walkDecls(decl => {
      assigned.set(decl.prop, tidy(decl.value));
    });
    found.set(name, assigned);
  });
  return found;
}

/**
 * The presets of `themes.css` — every `themes/<name>.css` its index imports,
 * in its order — each as the preset variables it assigns.
 */
export function presets(): ReadonlyMap<string, ReadonlyMap<string, string>> {
  presetCache ??= presetBlocks(themesSource());
  return presetCache;
}

let resetCache: ReadonlySet<string> | undefined;

/**
 * What the reset rule of `styles.css` empties on an element that names a
 * preset — read off the rule, not the registry, so a variable it forgets is
 * a variable an outer preset leaks through.
 */
export function resetVariables(): ReadonlySet<string> {
  if (resetCache) return resetCache;
  const cleared = new Set<string>();
  source('styles.css').walkAtRules('layer', layer => {
    if (layer.params !== 'fve-reset') return;
    layer.walkRules(rule => {
      if (rule.selector !== ':where([data-fve-preset])') return;
      rule.walkDecls(decl => {
        if (decl.value === 'initial') cleared.add(decl.prop);
      });
    });
  });
  resetCache = cleared;
  return cleared;
}

/**
 * The preset layer on an element that names `preset`, inside elements that
 * named `outer` ones (outermost first): each outer preset's values inherited
 * down, the reset emptying what it names at each preset, and the preset's
 * own values over it — the cascade, as far as the preset layer goes.
 */
function presetLayer(
  preset: string,
  outer: readonly string[],
  sources: ReadonlyMap<string, ReadonlyMap<string, string>>,
): Map<string, string> {
  const layer = new Map<string, string>();
  for (const name of [...outer, preset]) {
    const assigned = sources.get(name);
    if (!assigned) throw new Error(`no preset ${name}`);
    for (const variable of resetVariables()) layer.delete(variable);
    for (const [variable, value] of assigned) layer.set(variable, value);
  }
  return layer;
}

/** The names of the built-in presets, in the order `themes.css` writes them. */
export const PRESET_NAMES = [...presets().keys()];

/**
 * `var(--fve-x, fallback)` or `var(--fvp-x, fallback)`, split at its first
 * top-level comma — or with no fallback at all, a token with no built-in
 * value (the controls group), which is unset until a theme gives it one.
 */
function layerReference(
  value: string,
): [string, string | undefined] | undefined {
  const match = /^var\((--fv[ep]-[\w-]+)(?:,\s*([\s\S]+))?\)$/.exec(value);
  return match ? [match[1], match[2]?.trim()] : undefined;
}

/**
 * A preset value with every `var(--fve-*)` in it replaced by the host's
 * value, innermost first, a fallback taken where the host has none;
 * `undefined` when one is left with neither, as CSS makes it invalid.
 */
function substitute(value: string, host: HostVariables): string | undefined {
  const held: string[] = [];
  let text = value;
  const innermost = /var\((--fve-[\w-]+)(?:,\s*([^()]*))?\)/;
  for (let match = innermost.exec(text); match; match = innermost.exec(text)) {
    const [whole, variable, fallback] = match;
    const given =
      host[variable] ??
      fallback?.replace(/§(\d+)/g, (_, at: string) => held[Number(at)]);
    if (given === undefined) return undefined;
    held.push(given);
    text = text.replace(whole, `§${held.length - 1}`);
  }
  return text.replace(/§(\d+)/g, (_, at: string) => held[Number(at)]);
}

/** Where a preset sits and what is around it. */
export interface Placement {
  /** The presets on the elements around it, outermost first. */
  outer?: readonly string[];
  /** Presets beyond the built-in ones, by name — a host's own. */
  extra?: ReadonlyMap<string, ReadonlyMap<string, string>>;
}

/**
 * What each token of the surface is in one preset and one mode, as the CSS
 * text the cascade would hand on — the host's variable where it set one, a
 * preset variable the preset set, or the built-in value beside them.
 */
export function declared(
  preset: string,
  mode: Mode,
  convention: Convention = 'semantic',
  host: HostVariables = DEFAULT_HOST,
  { outer = [], extra }: Placement = {},
): Map<string, string> {
  const sources = extra ? new Map([...presets(), ...extra]) : presets();
  if (!sources.has(preset))
    throw new Error(`no preset ${preset} in themes.css`);
  const assigned = presetLayer(preset, outer, sources);
  const tokens = new Map<string, string>();
  // The layers in the order a token reads them: the host's variable, the
  // preset's, the built-in value. A preset value that reads a host variable
  // is substituted where the preset is declared; one that reads a variable
  // nobody set is invalid there, and the next layer is read.
  const layered = (value: string): string | undefined => {
    const reference = layerReference(value);
    if (!reference) return value;
    const [variable, fallback] = reference;
    const given = variable.startsWith('--fve-')
      ? host[variable]
      : assigned.get(variable);
    const substituted =
      given !== undefined && given !== 'initial'
        ? substitute(given, host)
        : undefined;
    if (substituted !== undefined) return substituted;
    return fallback === undefined ? undefined : layered(fallback);
  };
  const read = (declarations: Map<string, string>) => {
    for (const [token, value] of declarations) {
      const resolved = layered(value);
      // No value and no fallback: the token is unset (guaranteed-invalid),
      // and what reads it falls back on its own.
      if (resolved === undefined) tokens.delete(token);
      else tokens.set(token, resolved);
    }
  };
  read(conventionBlock(convention));
  read(block(LIGHT_BLOCK));
  if (mode === 'dark') read(block(DARK_BLOCK));
  return tokens;
}

/** A colour as sRGB with alpha, channels clipped to the gamut. */
function rgba(color: Color): Rgba {
  const rgb = toRgb(color);
  const clip = (channel: number) => Math.min(1, Math.max(0, channel));
  return {
    r: clip(rgb.r),
    g: clip(rgb.g),
    b: clip(rgb.b),
    alpha: rgb.alpha ?? 1,
  };
}

/**
 * How an `oklch()` outside sRGB reaches the screen: clipped channel by
 * channel, or carried back along chroma at its own lightness (CSS Color 4's
 * gamut mapping). Browsers differ, so the brand sweep holds both.
 */
export type Gamut = 'clip' | 'chroma';

const toOklch = converter('oklch');

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

/**
 * `oklch(from <colour> <l> <c> <h>)`, CSS Color 5's relative colour, as far
 * as the presets write it: each channel a number, the origin's own channel
 * (`l`, `c`, `h`), or `clamp()` / `min()` / `max()` of those.
 */
function relativeOklch(value: string): Color | undefined {
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

/** Splits a function's arguments at the commas that are not nested. */
function splitArguments(text: string): string[] {
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
 * The resolved colours of one preset in one mode: every colour token of the
 * surface, `var()` references and `color-mix(in oklab, …)` worked out the
 * way CSS Color 5 does (premultiplied, so a mix with `transparent` keeps the
 * hue and takes the alpha).
 */
export function resolveTokens(
  preset: string,
  mode: Mode,
  convention: Convention = 'semantic',
  host: HostVariables = DEFAULT_HOST,
  gamut: Gamut = 'clip',
  placement: Placement = {},
): Map<string, Rgba> {
  const text = declared(preset, mode, convention, host, placement);
  const resolved = new Map<string, Rgba>();

  const evaluate = (value: string, seen: string[]): Rgba => {
    const reference = /^var\((--[\w-]+)\)$/.exec(value);
    if (reference) return token(reference[1], seen);
    if (value === 'transparent') return { r: 0, g: 0, b: 0, alpha: 0 };
    const relative = relativeOklch(value);
    if (relative)
      return rgba(gamut === 'clip' ? relative : clampChroma(relative, 'oklch'));
    const mix = /^color-mix\(in oklab,\s*([\s\S]+)\)$/.exec(value);
    if (mix) {
      const [first, second] = splitArguments(mix[1]);
      const weighted = (part: string) => {
        const match = /^([\s\S]+?)\s+([\d.]+)%$/.exec(part);
        return match
          ? { color: evaluate(match[1], seen), weight: Number(match[2]) / 100 }
          : { color: evaluate(part, seen), weight: undefined };
      };
      const one = weighted(first);
      const other = weighted(second);
      const p = one.weight ?? 1 - (other.weight ?? 0.5);
      const q = 1 - p;
      const alpha = one.color.alpha * p + other.color.alpha * q;
      if (alpha === 0) return { r: 0, g: 0, b: 0, alpha: 0 };
      const a = toOklab({ mode: 'rgb', ...one.color });
      const b = toOklab({ mode: 'rgb', ...other.color });
      const channel = (x: number, y: number) =>
        (x * one.color.alpha * p + y * other.color.alpha * q) / alpha;
      return rgba({
        mode: 'oklab',
        l: channel(a.l, b.l),
        a: channel(a.a, b.a),
        b: channel(a.b, b.b),
        alpha,
      });
    }
    const parsed = parse(value);
    if (!parsed) throw new Error(`unreadable colour ${value}`);
    return rgba(parsed);
  };

  const token = (name: string, seen: string[]): Rgba => {
    const known = resolved.get(name);
    if (known) return known;
    if (seen.includes(name))
      throw new Error(`${[...seen, name].join(' → ')} is a cycle`);
    const value = text.get(name);
    if (value === undefined) throw new Error(`${name} is not a token`);
    const color = evaluate(value, [...seen, name]);
    resolved.set(name, color);
    return color;
  };

  for (const name of text.keys()) {
    // Lengths (`radius`, `text-ui`), weights (`title-weight`), keywords
    // (`focus-style`) and shadows (`shadow-*`, `card-shadow`) are not
    // colours: the registry says which a token is.
    if (!COLOR_VARIABLES.has(name)) continue;
    token(name, []);
  }
  return resolved;
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
