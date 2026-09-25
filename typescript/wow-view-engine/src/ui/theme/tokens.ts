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
 * The theme's contract, as data: every host variable a host or a preset may
 * write, and what the engine does with it (theme-architecture.md 5, D46).
 *
 * This is the one source the rest is made from or held to — the README's
 * token tables (`test/themeFiles.test.ts`, with the words in `tokenDocs.ts`),
 * the `FveToken` type, the rules `scripts/verify-package.mjs` checks the
 * built stylesheets by (through `dist/theme-tokens.json`, written from here
 * at build time), the contrast pairs both the jsdom suite and the browser
 * matrix measure (`pairs.ts`), and the tokens a chart reads and the
 * attributes it watches (below).
 *
 * It holds structure and never a value: the values are in `styles.css` and
 * the preset sources, which stay the runtime's one source of truth, and the
 * tests hold the two together — every entry the stylesheet declares where
 * this says, every `--fve-*` the package reads is here.
 */

/**
 * Who a token is for, by what it describes (theme-architecture.md 5.2):
 *
 * - `semantic` — shadcn's colour names and the engine's own derived ones.
 * - `role` — one surface of the engine's own; the layer S3 adds.
 * - `group` — a preset's parameter set, given whole or not at all.
 * - `axis` — an input a host gives beside any preset (the brand colour).
 * - `layout` — a host's length or level, not the theme's at all.
 */
export type TokenTier = 'semantic' | 'role' | 'group' | 'axis' | 'layout';

/** What a token's value is, and so how a tool reads it. */
export type TokenKind =
  'color' | 'length' | 'number' | 'shadow' | 'font' | 'keyword';

/** A preset's optional parameter sets (D35 Q62, D43). */
export type TokenGroup =
  | 'chart'
  | 'shadow'
  | 'font'
  | 'patterns'
  | 'density'
  | 'canvas'
  | 'card'
  | 'controls'
  | 'title';

export interface TokenEntry {
  /** The token, as `--fve-<name>` spells it. */
  readonly name: string;
  readonly tier: TokenTier;
  readonly kind: TokenKind;
  /** `2` when it has a dark half, `--fve-dark-<name>`. */
  readonly modes: 1 | 2;
  /**
   * The token blocks of `styles.css` declare `--<name>`, reading
   * `--fve-<name>` (and its dark half) with the built-in value behind it.
   * Otherwise the variable is read where it is used.
   */
  readonly block?: boolean;
  /** A preset may set it; `neutral` puts it back. */
  readonly preset?: boolean;
  /** `shadcn-bridge.css` points it at the host's shadcn token of its name. */
  readonly bridge?: boolean;
  /** A chart reads it (`CHART_TOKENS`). */
  readonly chart?: boolean;
  /** The optional group a preset gives it in. */
  readonly group?: TokenGroup;
  /** The token its built-in value is, when that is another token. */
  readonly fallback?: string;
}

/** Each optional group, and whether a preset has to give it whole. */
export const TOKEN_GROUPS: Readonly<
  Record<TokenGroup, { readonly whole: boolean }>
> = {
  chart: { whole: true },
  shadow: { whole: true },
  font: { whole: true },
  patterns: { whole: true },
  density: { whole: true },
  canvas: { whole: true },
  card: { whole: true },
  controls: { whole: true },
  title: { whole: true },
};

/** A colour of shadcn's, both modes, a preset's and the bridge's. */
const SHADCN = {
  tier: 'semantic',
  kind: 'color',
  modes: 2,
  block: true,
  preset: true,
  bridge: true,
} as const;

/** A colour of both modes a preset sets and the bridge leaves alone. */
const OWN = { ...SHADCN, bridge: false } as const;

/** One of the eight chart slots. */
const SLOT = {
  ...OWN,
  tier: 'group',
  group: 'chart',
  chart: true,
} as const;

/** One of the three lifts. */
const LIFT = {
  ...OWN,
  tier: 'group',
  kind: 'shadow',
  group: 'shadow',
} as const;

/** A host length or level, read where it is used. */
const LAYOUT = { tier: 'layout', modes: 1 } as const;

/**
 * Every host variable, in the order the README lists them. The private
 * variables the engine writes for itself (the expanded view's box, a pinned
 * column's offset, the chart's tap hint) are not the contract and are not
 * here; they leave the `--fve-` prefix in S2.
 */
export const TOKENS = [
  { name: 'background', ...SHADCN },
  { name: 'foreground', ...SHADCN, chart: true },
  { name: 'card', ...SHADCN },
  { name: 'card-foreground', ...SHADCN },
  { name: 'popover', ...SHADCN },
  { name: 'popover-foreground', ...SHADCN },
  { name: 'primary', ...SHADCN },
  { name: 'primary-foreground', ...SHADCN },
  { name: 'secondary', ...SHADCN },
  { name: 'secondary-foreground', ...SHADCN },
  { name: 'muted', ...SHADCN },
  { name: 'muted-foreground', ...SHADCN, chart: true },
  { name: 'accent', ...SHADCN },
  { name: 'accent-foreground', ...SHADCN },
  { name: 'sidebar', ...SHADCN },
  { name: 'sidebar-foreground', ...SHADCN },
  { name: 'sidebar-accent', ...SHADCN },
  { name: 'sidebar-accent-foreground', ...SHADCN },
  { name: 'sidebar-border', ...SHADCN },
  { name: 'destructive', ...OWN },
  { name: 'success', ...OWN },
  { name: 'warning', ...OWN },
  { name: 'border', ...SHADCN, chart: true },
  { name: 'input', ...OWN },
  { name: 'ring', ...OWN },
  { name: 'destructive-foreground', ...OWN, fallback: 'background' },
  { name: 'row-hover', ...OWN },
  { name: 'quiet-foreground', ...OWN },
  { name: 'pin-shadow', ...OWN, preset: false },
  { name: 'chart-1', ...SLOT },
  { name: 'chart-2', ...SLOT },
  { name: 'chart-3', ...SLOT },
  { name: 'chart-4', ...SLOT },
  { name: 'chart-5', ...SLOT },
  { name: 'chart-6', ...SLOT },
  { name: 'chart-7', ...SLOT },
  { name: 'chart-8', ...SLOT },
  { name: 'radius', ...SHADCN, kind: 'length', modes: 1 },
  { name: 'text-ui', ...OWN, kind: 'length', modes: 1, preset: false },
  {
    name: 'font-sans',
    tier: 'group',
    kind: 'font',
    modes: 1,
    preset: true,
    bridge: true,
    group: 'font',
  },
  {
    name: 'chart-patterns',
    tier: 'group',
    kind: 'keyword',
    modes: 1,
    preset: true,
    chart: true,
    group: 'patterns',
  },
  { name: 'brand', tier: 'axis', kind: 'color', modes: 2 },
  {
    name: 'preset-density',
    tier: 'group',
    kind: 'number',
    modes: 1,
    preset: true,
    group: 'density',
  },
  { name: 'rise', ...OWN, preset: false, chart: true },
  { name: 'fall', ...OWN, preset: false, chart: true },
  { name: 'shadow-sm', ...LIFT },
  { name: 'shadow-md', ...LIFT },
  { name: 'shadow-lg', ...LIFT },
  {
    name: 'canvas',
    ...OWN,
    tier: 'group',
    group: 'canvas',
    fallback: 'background',
  },
  { name: 'card-edge', ...OWN, tier: 'group', group: 'card' },
  { name: 'card-shadow', ...OWN, tier: 'group', kind: 'shadow', group: 'card' },
  { name: 'control', ...OWN, tier: 'group', group: 'controls' },
  { name: 'control-edge', ...OWN, tier: 'group', group: 'controls' },
  { name: 'control-thumb', ...OWN, tier: 'group', group: 'controls' },
  {
    name: 'title-weight',
    ...OWN,
    tier: 'group',
    kind: 'number',
    modes: 1,
    group: 'title',
  },
  { name: 'popup-z-index', ...LAYOUT, kind: 'number' },
  { name: 'record-table-max-h', ...LAYOUT, kind: 'length' },
  { name: 'record-text-max-w', ...LAYOUT, kind: 'length' },
  { name: 'workbench-min-height', ...LAYOUT, kind: 'length' },
] as const satisfies readonly TokenEntry[];

type Entry = (typeof TOKENS)[number];

/** A registered token's name. */
export type TokenName = Entry['name'];

/**
 * Every host variable of the contract: `--fve-<token>`, and
 * `--fve-dark-<token>` for a token with a dark half. What a host may set on
 * `:root`, any ancestor, or one surface.
 */
export type FveToken =
  `--fve-${TokenName}` | `--fve-dark-${Extract<Entry, { modes: 2 }>['name']}`;

/** The host variables one entry is written through, light first. */
export function hostVariables(entry: TokenEntry): string[] {
  return entry.modes === 2
    ? [`--fve-${entry.name}`, `--fve-dark-${entry.name}`]
    : [`--fve-${entry.name}`];
}

/**
 * The attributes, on a surface or an ancestor, that move what the tokens
 * resolve to, by the axis each carries (theme-architecture.md 1): the mode
 * (`class` for `.dark`, and any class a host themes by; `data-theme`, a
 * pinned mode), the preset, the change convention and the density — which
 * moves no colour, but a chart's cell, and so is watched too.
 */
export const THEME_AXES = [
  { name: 'mode', attributes: ['class', 'data-theme'] },
  { name: 'preset', attributes: ['data-fve-preset'] },
  { name: 'change-colors', attributes: ['data-fve-change-colors'] },
  { name: 'density', attributes: ['data-fve-density'] },
] as const;

/**
 * Every attribute a chart watches to know its theme moved: the axes', and
 * `style`, where a host sets the tokens themselves inline.
 */
export const THEME_ATTRIBUTES: readonly string[] = [
  ...THEME_AXES.flatMap(({ attributes }) => attributes),
  'style',
];

/**
 * Every variable a chart reads, the eight slots first and in their order:
 * a token the blocks declare by its own name (`--chart-1`, `--rise`), one
 * read where it is used by its host name (`--fve-chart-patterns`).
 */
export const CHART_TOKENS: readonly string[] = (() => {
  const read: readonly TokenEntry[] = TOKENS.filter(
    (entry: TokenEntry) => entry.chart,
  );
  const slot = (entry: TokenEntry) => entry.group === 'chart';
  return [...read.filter(slot), ...read.filter(entry => !slot(entry))].map(
    entry => (entry.block ? `--${entry.name}` : `--fve-${entry.name}`),
  );
})();
