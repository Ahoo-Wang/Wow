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
 * theme-check (theme-architecture.md 5.2, D46 Q6, S7): a host's theme held
 * to the gates the built-in presets are held to, before it ships.
 *
 * It reads the host's stylesheet as the package's contract reads it —
 * the host's layer (`--fve-*` on `:root` or any selector that is not a
 * preset's), the host's own presets (`:where([data-fve-preset='…'])`
 * blocks, `--fvp-*`), and a bridge of its own
 * (`:where(:root:not([data-fve-preset]))`) — and reports:
 *
 * - **the registry**: a `--fve-*` or `--fvp-*` the registry does not list
 *   (it does nothing), a `--_fve-*` written or read (the engine's own), a
 *   preset variable outside a preset or a host variable inside one, a
 *   preset inside an `@layer` (the reset beats it), a group given in part
 *   (the chart palette, the shadows), `initial` in a preset (the reset
 *   already does it);
 * - **Tailwind v3's HSL channels** (`--primary: 222.2 47.4% 11.2%`): no
 *   colour on their own, so a bridge or a variable that reads them is
 *   invalid — with the `hsl()` it wants;
 * - **the brand's bounds**: a bound out of range, or a lower bound above
 *   its upper one; and, where the theme moves a bound, a sweep of brand
 *   colours across sRGB, since a bound is a promise for every colour;
 * - **contrast**: every pair of the registry (`pairs.ts`) in both modes and
 *   every change convention, on each preset the theme is worn on;
 * - **the chart palette**, where the theme brings one: the three gates of
 *   themes.md 5.2.
 *
 * The resolution and the arithmetic are `resolve.ts`, which the package's
 * own suites measure the built-in presets by.
 */

import {
  type Color,
  differenceEuclidean,
  filterDeficiencyDeuter,
  filterDeficiencyProt,
  filterDeficiencyTrit,
  clampChroma,
  formatHex,
} from 'culori';
import postcss from 'postcss';
import type { Registry } from './registry';
import {
  CONVENTIONS,
  contrast,
  type Gamut,
  type HostVariables,
  type Mode,
  type Resolver,
  type Rgba,
} from './resolve';

export type Severity = 'error' | 'warning';

/** One thing theme-check found, where it found it. */
export interface Finding {
  readonly severity: Severity;
  /** Which check: `registry`, `layer`, `hsl`, `brand`, `contrast`, `palette`. */
  readonly check: string;
  readonly message: string;
  /** `line:column` in the host's stylesheet, where there is one. */
  readonly at?: string;
}

export interface CheckOptions {
  /**
   * The built-in presets the host's own variables are worn on; every one
   * when left out. The host's own presets are always measured.
   */
  readonly presets?: readonly string[];
  /** `data-fve-brand-chart` is on: the first chart slot takes the brand. */
  readonly brandChart?: boolean;
}

const MODES: readonly Mode[] = ['light', 'dark'];

/** What a preset rule names, or undefined for a rule no preset selects. */
const presetOf = (selector: string) =>
  /data-fve-preset=['"]?([\w-]+)['"]?\]/.exec(selector)?.[1];

/** A bridge's rule: `:root` while `<html>` names no preset. */
const isBridge = (selector: string) =>
  /:root:not\(\[data-fve-preset\]\)/.test(selector);

/** Tailwind v3's `H S% L%`: three numbers, the last two percentages. */
const HSL_CHANNELS = /^-?[\d.]+(deg)?\s+[\d.]+%\s+[\d.]+%(\s*\/\s*[\d.]+%?)?$/;

const where = (node: postcss.Node) =>
  node.source?.start
    ? `${node.source.start.line}:${node.source.start.column}`
    : undefined;

/** What a host's stylesheet says, as a theme to resolve. */
interface HostTheme {
  /** The host's variables set on `:root` or `html`, as the page gets them. */
  host: Record<string, string>;
  /** Each host preset, by name, as the preset variables it assigns. */
  presets: Map<string, Map<string, string>>;
  /** The host's variables its preset blocks set, by preset. */
  presetHost: Map<string, Record<string, string>>;
}

/** Every theme-check finding for one host stylesheet. */
export function checkTheme(
  css: string,
  resolver: Resolver,
  registry: Registry,
  options: CheckOptions = {},
): Finding[] {
  const findings: Finding[] = [];
  const add = (
    severity: Severity,
    check: string,
    message: string,
    at?: string,
  ) => findings.push({ severity, check, message, at });

  let root: postcss.Root;
  try {
    root = postcss.parse(css);
  } catch (error) {
    add('error', 'parse', (error as Error).message);
    return findings;
  }

  const host = new Set(registry.tokens.flatMap(token => token.variables));
  const preset = new Set(
    registry.tokens.flatMap(token => token.presetVariables),
  );
  const colours = new Set(
    registry.tokens
      .filter(token => token.kind === 'color')
      .flatMap(token => [...token.variables, ...token.presetVariables]),
  );
  const channels = new Map<string, string>();
  // The host's own variables on `:root`, which its theme may read.
  const own = new Map<string, string>();
  root.walkDecls(/^--/, decl => {
    if (HSL_CHANNELS.test(decl.value.trim()))
      channels.set(decl.prop, decl.value.trim());
    const rule = decl.parent as postcss.Rule;
    if (
      !decl.prop.startsWith('--fv') &&
      rule.type === 'rule' &&
      /^(:root|html)$/.test(rule.selector.trim())
    )
      own.set(decl.prop, decl.value.trim());
  });
  /** A value with the host's own `var()`s put in, as `:root` resolves them. */
  const inline = (value: string, depth = 0): string | undefined => {
    if (depth > 8) return undefined;
    let unresolved = false;
    const text = value.replace(
      /var\((--(?!fv|_fve)[\w-]+)(?:,\s*([^()]*))?\)/g,
      (_, name: string, fallback?: string) => {
        const found = own.get(name) ?? fallback;
        if (found === undefined) unresolved = true;
        return found ?? '';
      },
    );
    if (unresolved) return undefined;
    return /var\((--(?!fv|_fve)[\w-]+)/.test(text)
      ? inline(text, depth + 1)
      : text;
  };

  const theme: HostTheme = {
    host: {},
    presets: new Map(),
    presetHost: new Map(),
  };

  root.walkRules(rule => {
    const named = presetOf(rule.selector);
    const bridge = isBridge(rule.selector);
    const layered = parents(rule).some(
      node =>
        node.type === 'atrule' && (node as postcss.AtRule).name === 'layer',
    );
    if (named && layered)
      add(
        'error',
        'layer',
        `the preset '${named}' is inside an @layer: the reset (@layer fve-reset) beats it and it paints nothing; write it outside any layer`,
        where(rule),
      );
    rule.each(node => {
      if (node.type !== 'decl') return;
      const { prop, value } = node;
      const at = where(node);
      if (prop.startsWith('--_fve-'))
        add(
          'error',
          'registry',
          `${prop} is the engine's own: never write it`,
          at,
        );
      else if (prop.startsWith('--fvp-')) {
        if (!preset.has(prop))
          add(
            'error',
            'registry',
            `${prop} is no preset variable: it does nothing`,
            at,
          );
        else if (!named && !bridge)
          add(
            'warning',
            'layer',
            `${prop} is a preset variable outside a preset block (${rule.selector}): a preset named inside it empties it`,
            at,
          );
        if (value.trim() === 'initial')
          add(
            'warning',
            'registry',
            `${prop}: initial — the reset already empties the preset layer; leave the variable out`,
            at,
          );
      } else if (prop.startsWith('--fve-')) {
        if (!host.has(prop))
          add(
            'error',
            'registry',
            `${prop} is no host variable: it does nothing`,
            at,
          );
        else if (named && prop !== '--fve-brand' && prop !== '--fve-dark-brand')
          add(
            'warning',
            'layer',
            `${prop} in the preset '${named}' is a host variable: every preset pinned inside that element loses to it; write --fvp-${prop.slice(6)}`,
            at,
          );
      }
      for (const [, read] of value.matchAll(/var\((--[\w-]+)/g)) {
        if (read.startsWith('--_fve-'))
          add(
            'warning',
            'registry',
            `${prop} reads ${read}, the engine's own: it changes without notice`,
            at,
          );
        else if (read.startsWith('--fve-') && !host.has(read))
          add(
            'error',
            'registry',
            `${prop} reads ${read}, no host variable`,
            at,
          );
        else if (read.startsWith('--fvp-') && !preset.has(read))
          add(
            'error',
            'registry',
            `${prop} reads ${read}, no preset variable`,
            at,
          );
        else if (
          colours.has(prop) &&
          channels.has(read) &&
          new RegExp(`^var\\(${read}\\s*[,)]`).test(value.trim())
        )
          add(
            'error',
            'hsl',
            `${prop} reads ${read}, Tailwind v3's HSL channels (${channels.get(read)}): no colour on its own; write hsl(var(${read}))`,
            at,
          );
      }
      if (colours.has(prop) && HSL_CHANNELS.test(value.trim()))
        add(
          'error',
          'hsl',
          `${prop}: ${value.trim()} is HSL channels, no colour; write hsl(${value.trim()})`,
          at,
        );
      if (!prop.startsWith('--fv')) return;
      // A value that reads a variable the page does not give on `:root` is
      // the page's to resolve (a bridge's `var(--primary)` under `.dark`):
      // nothing here to measure it by.
      const resolved = inline(value.trim());
      if (resolved === undefined) return;
      if (named) {
        if (prop.startsWith('--fvp-')) {
          const assigned = theme.presets.get(named) ?? new Map();
          assigned.set(prop, resolved);
          theme.presets.set(named, assigned);
        } else {
          const given = theme.presetHost.get(named) ?? {};
          given[prop] = resolved;
          theme.presetHost.set(named, given);
        }
      } else if (/^(:root|html)$/.test(rule.selector.trim()))
        theme.host[prop] = resolved;
    });
  });

  // Tailwind v3's shadcn theme under the shipped bridge.
  const shadcn = registry.tokens.filter(token => token.bridge);
  const v3 = shadcn.filter(token => channels.has(`--${token.name}`));
  let bridged = false;
  root.walkRules(rule => {
    if (isBridge(rule.selector)) bridged = true;
  });
  if (v3.length && !bridged)
    add(
      'warning',
      'hsl',
      `${v3.map(token => `--${token.name}`).join(', ')}: Tailwind v3 HSL channels, not colours — shadcn-bridge.css reads Tailwind v4 colours and would bridge these as invalid values; write the bridge yourself, :where(:root:not([data-fve-preset])) { --fvp-${v3[0].name}: hsl(var(--${v3[0].name})); … }`,
    );

  // A group a preset gives in part.
  for (const [name, assigned] of theme.presets)
    for (const [group, { whole }] of Object.entries(registry.groups)) {
      if (!whole) continue;
      const members = registry.tokens
        .filter(token => token.group === group)
        .flatMap(token => token.presetVariables);
      const given = members.filter(variable => assigned.has(variable));
      if (given.length && given.length < members.length)
        add(
          'error',
          'registry',
          `the preset '${name}' gives ${given.length} of the ${members.length} variables of the ${group} group: give it whole, both modes, or leave it out`,
        );
    }

  const bounds = checkBounds(theme, add);
  if (findings.some(finding => finding.severity === 'error')) return findings;

  // Where the theme is worn: its own presets, and the built-in ones its
  // host variables sit over.
  const extra = new Map(
    [...theme.presets].map(([name, assigned]) => [name, assigned] as const),
  );
  for (const name of theme.presetHost.keys())
    if (!extra.has(name)) extra.set(name, new Map());
  const hosted = Object.keys(theme.host).length > 0;
  const worn: { preset: string; host: HostVariables }[] = [
    ...[...extra.keys()].map(name => ({
      preset: name,
      host: { ...theme.host, ...theme.presetHost.get(name) },
    })),
    ...(hosted || extra.size === 0
      ? (options.presets ?? resolver.presetNames).map(name => ({
          preset: name,
          host: theme.host,
        }))
      : []),
  ];
  const placement = { extra, brandChart: options.brandChart };

  for (const { preset: name, host: variables } of worn) {
    const gamuts: readonly Gamut[] =
      variables['--fve-brand'] || variables['--fve-dark-brand']
        ? ['clip', 'chroma']
        : ['clip'];
    for (const mode of MODES) {
      // A pair short in every convention is reported once; one short only
      // under a convention (a rise or fall mark) says which.
      const short = new Map<
        string,
        { conventions: string[]; reading: string }
      >();
      for (const convention of CONVENTIONS) {
        let measured;
        try {
          measured = gamuts.flatMap(gamut =>
            resolver.measure(
              name,
              mode,
              convention,
              variables,
              gamut,
              placement,
            ),
          );
        } catch (error) {
          add(
            'error',
            'contrast',
            `${name}/${mode}: ${(error as Error).message}`,
          );
          break;
        }
        const seen = new Set<string>();
        for (const { name: pair, ratio, line } of measured) {
          if (ratio >= line || seen.has(pair)) continue;
          seen.add(pair);
          const entry = short.get(pair) ?? {
            conventions: [],
            reading: `${ratio.toFixed(2)}:1 < ${line}:1`,
          };
          entry.conventions.push(convention);
          short.set(pair, entry);
        }
      }
      for (const [pair, { conventions, reading }] of short)
        add(
          'error',
          'contrast',
          `${name}/${mode}${conventions.length === CONVENTIONS.length ? '' : ` (${conventions.join(', ')})`}: ${pair} ${reading}`,
        );
    }
  }

  // A bound moved: a promise for every brand colour, so sweep them.
  if (bounds.length)
    for (const { preset: name, host: variables } of worn)
      for (const mode of MODES) {
        const short = new Map<string, string>();
        for (const brand of SWEEP)
          for (const gamut of ['clip', 'chroma'] as const)
            for (const { name: pair, ratio, line } of resolver.measure(
              name,
              mode,
              'semantic',
              { ...variables, '--fve-brand': brand, '--fve-dark-brand': brand },
              gamut,
              placement,
            ))
              if (ratio < line && !short.has(pair))
                short.set(pair, `${brand} ${ratio.toFixed(2)}:1 < ${line}:1`);
        for (const [pair, reading] of short)
          add(
            'error',
            'brand',
            `${name}/${mode}: the brand bounds (${bounds.join(', ')}) let ${pair} fall short, e.g. ${reading}`,
          );
      }

  // The chart palette, where the theme brings one or the brand takes slot 1.
  const palettes = /--fv[ep]-(dark-)?chart-[1-8]$/;
  for (const { preset: name, host: variables } of worn) {
    const brings =
      options.brandChart ||
      Object.keys(variables).some(variable => palettes.test(variable)) ||
      [...(extra.get(name)?.keys() ?? [])].some(variable =>
        palettes.test(variable),
      );
    if (!brings) continue;
    for (const mode of MODES)
      for (const problem of paletteGates(
        resolver.resolveTokens(
          name,
          mode,
          'semantic',
          variables,
          'clip',
          placement,
        ),
        mode,
      ))
        add(problem.severity, 'palette', `${name}/${mode}: ${problem.message}`);
  }

  return findings;
}

/** The rules and at-rules a node sits in, innermost first. */
function parents(node: postcss.Node): postcss.Node[] {
  const found: postcss.Node[] = [];
  for (let at = node.parent; at && at.type !== 'root'; at = at.parent)
    found.push(at as postcss.Node);
  return found;
}

/** The brand bounds a theme moves: range-checked, and named for the sweep. */
function checkBounds(
  theme: HostTheme,
  add: (severity: Severity, check: string, message: string) => void,
): string[] {
  const moved: string[] = [];
  const sets: [string, Record<string, string>][] = [
    ['the host', theme.host],
    ...[...theme.presets].map(
      ([name, assigned]) =>
        [`the preset '${name}'`, Object.fromEntries(assigned)] as [
          string,
          Record<string, string>,
        ],
    ),
    ...[...theme.presetHost].map(
      ([name, own]) =>
        [`the preset '${name}'`, own] as [string, Record<string, string>],
    ),
  ];
  for (const [who, variables] of sets) {
    const bound = (name: string) =>
      Object.entries(variables).find(([variable]) =>
        new RegExp(`^--fv[ep]-${name}$`).test(variable),
      );
    for (const [variable, value] of Object.entries(variables)) {
      if (!/^--fv[ep]-(dark-)?brand-/.test(variable)) continue;
      moved.push(variable);
      const numbers = value.split(/\s+/).map(Number);
      const lc = variable.endsWith('-lc');
      if (
        numbers.some(Number.isNaN) ||
        numbers.length !== (lc ? 2 : 1) ||
        numbers[0] < 0 ||
        numbers[0] > 1 ||
        (lc && (numbers[1] < 0 || numbers[1] > 0.4)) ||
        (/-c-max$/.test(variable) && numbers[0] > 0.4)
      )
        add(
          'error',
          'brand',
          `${who}: ${variable}: ${value} — ${lc ? 'a lightness 0–1 and a chroma 0–0.4' : 'a number 0–1'}`,
        );
    }
    for (const dark of ['', 'dark-'])
      for (const [low, high] of [
        ['brand-l-min', 'brand-l-max'],
        ['brand-ring-l-min', 'brand-ring-l-max'],
      ]) {
        const min = bound(`${dark}${low}`);
        const max = bound(`${dark}${high}`);
        if (min && max && Number(min[1]) > Number(max[1]))
          add(
            'error',
            'brand',
            `${who}: ${min[0]} (${min[1]}) is above ${max[0]} (${max[1]}): the clamp would pin every brand to the lower bound`,
          );
      }
  }
  return [...new Set(moved)];
}

/** Brand colours across sRGB, every hue from grey to full, as hex. */
const SWEEP = [
  ...new Set(
    Array.from({ length: 12 }, (_, step) => step * 30).flatMap(h =>
      [0.15, 0.35, 0.48, 0.58, 0.72, 0.85, 0.95].flatMap(l =>
        [0, 0.06, 0.14, 0.22, 0.32].map(c =>
          formatHex(clampChroma({ mode: 'oklch', l, c, h }, 'oklch')),
        ),
      ),
    ),
  ),
];

const SLOTS = 8;
const distance = differenceEuclidean('oklab');

/** A full-colour eye, and the three deficiencies at full strength. */
const VISION: Readonly<
  Record<string, { see: (color: Color) => Color; line: number }>
> = {
  normal: { see: (color: Color) => color, line: 15 },
  protanopia: { see: filterDeficiencyProt(1), line: 8 },
  deuteranopia: { see: filterDeficiencyDeuter(1), line: 8 },
  tritanopia: { see: filterDeficiencyTrit(1), line: 6 },
};

const toColor = (rgba: Rgba): Color => ({ mode: 'rgb', ...rgba });

/**
 * The palette's three gates (themes.md 5.2), as Storybook's palette page
 * and the package's suites hold them: neighbours apart to every eye, a mark
 * off the card at 3:1 (a light slot under it listed, not failed), and one
 * of the chart's two inks at 4.5:1 inside every mark.
 */
function paletteGates(
  tokens: ReadonlyMap<string, Rgba>,
  mode: Mode,
): { severity: Severity; message: string }[] {
  const problems: { severity: Severity; message: string }[] = [];
  const palette = Array.from({ length: SLOTS }, (_, index) => {
    const color = tokens.get(`--chart-${index + 1}`);
    if (!color) throw new Error(`--chart-${index + 1} did not resolve`);
    return color;
  });
  for (const [vision, { see, line }] of Object.entries(VISION)) {
    let worst = { apart: Infinity, slots: [0, 0] };
    palette.forEach((color, index) => {
      const next = (index + 1) % SLOTS;
      const apart =
        distance(see(toColor(color)), see(toColor(palette[next]))) * 100;
      if (apart < worst.apart) worst = { apart, slots: [index + 1, next + 1] };
    });
    if (worst.apart < line)
      problems.push({
        severity: 'error',
        message: `slots ${worst.slots.join(' and ')} are ${worst.apart.toFixed(1)} apart to ${vision} (< ${line})`,
      });
  }
  const card = tokens.get('--card')!;
  const page = tokens.get('--background')!;
  const ink = tokens.get('--foreground')!;
  palette.forEach((color, index) => {
    const ratio = contrast(color, card);
    if (ratio < 3)
      problems.push({
        severity: mode === 'dark' ? 'error' : 'warning',
        message: `slot ${index + 1} stands ${ratio.toFixed(2)}:1 off the card (< 3:1)${mode === 'light' ? ': a light slot under it is yours to accept, answered by the reading table and the patterns' : ''}`,
      });
    const opaque = { ...color, alpha: 1 };
    if (
      [page, card].some(
        ground =>
          Math.max(contrast(ink, opaque), contrast(ground, opaque)) < 4.5,
      )
    )
      problems.push({
        severity: 'error',
        message: `slot ${index + 1}: neither the foreground nor the ground reads 4.5:1 inside it`,
      });
  });
  return problems;
}
