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
 * just the two rules those files keep: every token is
 * `var(--fve-[dark-]<name>, <built-in>)`, and a preset assigns a value to
 * that host variable or `initial`, which leaves it unset.
 *
 * The WCAG arithmetic is the one `stories/view-engine/contrast.ts` and the
 * chart's `inkOn` use: a half-transparent layer composed over what it lies
 * on in gamma-encoded sRGB, the way the browser paints it, channels clipped
 * to the gamut, then relative luminance and `(lighter + 0.05) / (darker +
 * 0.05)` — culori's `wcagContrast`.
 */

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { type Color, converter, parse, wcagContrast } from 'culori';
import postcss from 'postcss';
import { themesSource } from '../../scripts/themes.mjs';

const source = (file: string) =>
  readFileSync(join(import.meta.dirname, '..', '..', 'src', file), 'utf8');

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

/** Every custom property one rule of `styles.css` declares, as written. */
function block(selector: string): Map<string, string> {
  const declared = new Map<string, string>();
  postcss.parse(source('styles.css')).walkRules(rule => {
    if (rule.selector !== selector) return;
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
  postcss.parse(source('styles.css')).walkRules(rule => {
    const crossed = rule.selector.includes("data-fve-change-colors='red-up'");
    if (
      !rule.some(
        node => node.type === 'decl' && node.prop === '--convention-rise',
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

/**
 * The presets of `themes.css` — every `themes/<name>.css` its index imports,
 * in its order — each as the host variables it assigns.
 */
export function presets(): Map<string, Map<string, string>> {
  const found = new Map<string, Map<string, string>>();
  postcss.parse(themesSource()).walkRules(rule => {
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

/** The names of the built-in presets, in the order `themes.css` writes them. */
export const PRESET_NAMES = [...presets().keys()];

/** `var(--fve-x, fallback)`, split at its first top-level comma. */
function hostReference(value: string): [string, string] | undefined {
  const match = /^var\((--fve-[\w-]+),\s*([\s\S]+)\)$/.exec(value);
  return match ? [match[1], match[2].trim()] : undefined;
}

/**
 * What each token of the surface is in one preset and one mode, as the CSS
 * text the cascade would hand on — a host variable the preset set, or the
 * built-in value beside it.
 */
export function declared(
  preset: string,
  mode: Mode,
  convention: Convention = 'semantic',
): Map<string, string> {
  const assigned = presets().get(preset);
  if (!assigned) throw new Error(`no preset ${preset} in themes.css`);
  const tokens = new Map<string, string>();
  const read = (declarations: Map<string, string>) => {
    for (const [token, value] of declarations) {
      const reference = hostReference(value);
      if (!reference) {
        tokens.set(token, value);
        continue;
      }
      const [host, fallback] = reference;
      const given = assigned.get(host);
      tokens.set(token, given && given !== 'initial' ? given : fallback);
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
): Map<string, Rgba> {
  const text = declared(preset, mode, convention);
  const resolved = new Map<string, Rgba>();

  const evaluate = (value: string, seen: string[]): Rgba => {
    const reference = /^var\((--[\w-]+)\)$/.exec(value);
    if (reference) return token(reference[1], seen);
    if (value === 'transparent') return { r: 0, g: 0, b: 0, alpha: 0 };
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

  for (const [name, value] of text) {
    // Lengths (`radius`, `text-ui`) and shadows are not colours.
    if (/^[\d.]+(rem|px)$/.test(value) || name.startsWith('--shadow-'))
      continue;
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
