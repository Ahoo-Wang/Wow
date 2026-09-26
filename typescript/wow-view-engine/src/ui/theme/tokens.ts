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
 *
 * Three layers write a token (theme-architecture.md 3, S2): the host writes
 * `--fve-<name>`, a preset — a built-in one, a host's own or the shadcn
 * bridge — writes `--fvp-<name>`, and the stylesheet reads
 * `var(--fve-<name>, var(--fvp-<name>, <built-in>))`, so the host is read
 * first under any nesting and any pin. What the engine derives or measures
 * for itself is `--_fve-*` and is not here: it is no one's to write.
 */

/**
 * Who a token is for, by what it describes (theme-architecture.md 5.2):
 *
 * - `semantic` — shadcn's colour names and the engine's own derived ones.
 * - `role` — one surface of the engine's own, falling back to a semantic
 *   token or to what was drawn before it existed (S3).
 * - `group` — a preset's parameter set: some given whole or not at all.
 * - `axis` — an input a host gives beside any preset (the brand colour).
 * - `layout` — a host's length or level, not the theme's at all.
 */
export type TokenTier = 'semantic' | 'role' | 'group' | 'axis' | 'layout';

/** What a token's value is, and so how a tool reads it. */
export type TokenKind =
  'color' | 'length' | 'number' | 'shadow' | 'font' | 'keyword';

/**
 * A preset's optional parameter sets (D35 Q62): a palette, a ladder of
 * lifts, a font stack, a switch, a step and the bounds a brand colour is
 * held to on its grounds — none of them one surface of the engine's. The groups that were surfaces (`canvas`, `card`,
 * `controls`, `title`, D43) are roles since S3 (theme-architecture.md 4.2).
 */
export type TokenGroup =
  'chart' | 'shadow' | 'font' | 'patterns' | 'density' | 'brand';

/**
 * Which part of the surface a role paints (theme-architecture.md 4.2): the
 * grounds and the cards on them, the tables, what marks a state, focus, the
 * controls, the shapes, the type, what floats over the rest, and the charts
 * (6.2), which the library draws off the cascade and so read theirs back.
 */
export type RoleArea =
  | 'surface'
  | 'table'
  | 'state'
  | 'focus'
  | 'control'
  | 'shape'
  | 'type'
  | 'float'
  | 'chart';

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
  /**
   * The block declares it under the engine's own name, `--_fve-<name>`,
   * rather than `--<name>`: a name of this package's, not one of shadcn's,
   * so the tokens boundary on a host's chrome does not shadow a variable of
   * the host's that happens to share it (theme-architecture.md 3.2).
   */
  readonly own?: boolean;
  /**
   * A preset may set it, as `--fvp-<name>`; the reset rule of `styles.css`
   * (`@layer fve-reset`) clears it on every element that names a preset.
   */
  readonly preset?: boolean;
  /** `shadcn-bridge.css` points it at the host's shadcn token of its name. */
  readonly bridge?: boolean;
  /** A chart reads it (`CHART_TOKENS`). */
  readonly chart?: boolean;
  /** The optional group a preset gives it in. */
  readonly group?: TokenGroup;
  /** The part of the surface a role paints; every role has one. */
  readonly area?: RoleArea;
  /** The token its built-in value is, when that is another token. */
  readonly fallback?: string;
  /**
   * Derived from the host's brand colour (`--fve-brand`) when there is one
   * (theme-architecture.md 2, S4): the block reads
   * `var(--fve-<name>, var(--_fve-brand-<name>, var(--fvp-<name>, …)))`, so
   * the host's own value beats the brand and the brand beats the preset's
   * literal. With no brand colour the derived value is invalid and the
   * preset's literal is read, as before.
   */
  readonly brand?: boolean;
  /**
   * A link (theme-architecture.md 9.3): this token says how much of a token
   * the surface has resolved — `to` — the role `role` is drawn in. The
   * stylesheet's link rule declares
   * `--_fve-link-<role>: color-mix(in oklab, var(<to>) var(--_fve-<name>),
   * transparent)`, and the role reads it between the host and the preset:
   * `var(--fve-<role>, var(--_fve-link-<role>, var(--fvp-<role>, …)))`.
   * Unset, the link is invalid and the role reads the preset, as before.
   * Resolved on the surface, so it follows a brand colour, a host's own
   * `--fve-primary` and the mode, which a preset's value cannot: a preset
   * block sits where its attribute is, above the surface, and a `var()` in
   * it is worked out there.
   */
  readonly link?: { readonly role: string; readonly to: string };
}

/**
 * Each optional group, and whether a preset has to give it whole. Only a
 * set whose members are one design has to be: the eight chart slots are
 * measured against each other for colour-vision distance, and the three
 * lifts are one ladder. Any other member a preset leaves out is the
 * built-in value, since the reset rule clears what an outer preset gave
 * (theme-architecture.md 3.6).
 */
export const TOKEN_GROUPS: Readonly<
  Record<TokenGroup, { readonly whole: boolean }>
> = {
  chart: { whole: true },
  shadow: { whole: true },
  font: { whole: false },
  patterns: { whole: false },
  density: { whole: false },
  brand: { whole: false },
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

/** One of those under the engine's own name, `--_fve-<name>`. */
const ENGINE = { ...OWN, own: true } as const;

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

/**
 * A role (theme-architecture.md 4): one surface of the engine's own, under
 * the engine's name, which a host and a preset may both set. Unset it is
 * the token named as its `fallback`, or the value drawn before the role
 * existed, so a theme that sets no role looks as it did.
 */
const role = (area: RoleArea) =>
  ({
    tier: 'role',
    kind: 'color',
    modes: 2,
    block: true,
    own: true,
    preset: true,
    area,
  }) as const;

/** A role that is one value in both modes: a length, a weight, a keyword. */
const measure = (area: RoleArea, kind: 'length' | 'number' | 'keyword') =>
  ({ ...role(area), kind, modes: 1 }) as const;

/**
 * One of the bounds a preset holds a brand colour to (theme-architecture.md
 * 2.2): numbers the derivation in `styles.css` reads, measured against the
 * preset's own grounds. Unset, the stylesheet's own bound applies.
 */
const BOUND = {
  tier: 'group',
  kind: 'number',
  modes: 2,
  preset: true,
  group: 'brand',
} as const;

/**
 * A link (theme-architecture.md 9.3): the share of the resolved `to` a
 * colour role is drawn in, one number for both modes — `to` resolves in
 * each. No built-in value: unset, the role is what it was.
 */
const link = (area: RoleArea, linked: string, to: string) =>
  ({
    ...role(area),
    kind: 'number',
    modes: 1,
    link: { role: linked, to },
  }) as const;

/** A host length or level, read where it is used. */
const LAYOUT = { tier: 'layout', modes: 1 } as const;

/**
 * Every host variable, in the order the README lists them. The private
 * variables the engine writes for itself (the expanded view's box, a pinned
 * column's offset, the chart's tap hint, a popup's type) are not the
 * contract and are not here: they are `--_fve-*`.
 */
export const TOKENS = [
  { name: 'background', ...SHADCN },
  { name: 'foreground', ...SHADCN, chart: true },
  { name: 'card', ...SHADCN },
  { name: 'card-foreground', ...SHADCN },
  { name: 'popover', ...SHADCN },
  { name: 'popover-foreground', ...SHADCN },
  { name: 'primary', ...SHADCN, brand: true },
  { name: 'primary-foreground', ...SHADCN },
  { name: 'secondary', ...SHADCN },
  { name: 'secondary-foreground', ...SHADCN },
  { name: 'muted', ...SHADCN },
  { name: 'muted-foreground', ...SHADCN, chart: true },
  { name: 'accent', ...SHADCN, brand: true },
  { name: 'accent-foreground', ...SHADCN },
  { name: 'sidebar', ...SHADCN },
  { name: 'sidebar-foreground', ...SHADCN },
  { name: 'sidebar-accent', ...SHADCN, brand: true },
  { name: 'sidebar-accent-foreground', ...SHADCN },
  { name: 'sidebar-border', ...SHADCN },
  // A category's tone paints its slice in these (`charts/palette.ts`).
  { name: 'destructive', ...OWN, chart: true },
  { name: 'success', ...OWN, chart: true },
  { name: 'warning', ...OWN, chart: true },
  { name: 'border', ...SHADCN },
  { name: 'input', ...OWN },
  { name: 'ring', ...OWN, brand: true },
  { name: 'destructive-foreground', ...OWN, fallback: 'background' },
  { name: 'quiet-foreground', ...ENGINE },
  { name: 'pin-shadow', ...ENGINE, preset: false },
  { name: 'chart-1', ...SLOT, brand: true },
  { name: 'chart-2', ...SLOT },
  { name: 'chart-3', ...SLOT },
  { name: 'chart-4', ...SLOT },
  { name: 'chart-5', ...SLOT },
  { name: 'chart-6', ...SLOT },
  { name: 'chart-7', ...SLOT },
  { name: 'chart-8', ...SLOT },
  { name: 'radius', ...SHADCN, kind: 'length', modes: 1 },
  { name: 'text-ui', ...ENGINE, kind: 'length', modes: 1, preset: false },
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
  // The bounds a preset holds the brand to: the primary's lightness and
  // chroma, the focus ring's lightness (unset: the ring is not derived), and
  // the lightness and chroma of each tint and of the first chart slot.
  { name: 'brand-l-min', ...BOUND },
  { name: 'brand-l-max', ...BOUND },
  { name: 'brand-c-max', ...BOUND },
  { name: 'brand-ring-l-min', ...BOUND },
  { name: 'brand-ring-l-max', ...BOUND },
  { name: 'brand-accent-lc', ...BOUND },
  { name: 'brand-sidebar-accent-lc', ...BOUND },
  { name: 'brand-row-selected-lc', ...BOUND },
  { name: 'brand-chart-1-lc', ...BOUND },
  {
    name: 'preset-density',
    tier: 'group',
    kind: 'number',
    modes: 1,
    preset: true,
    group: 'density',
  },
  { name: 'rise', ...ENGINE, preset: false, chart: true },
  { name: 'fall', ...ENGINE, preset: false, chart: true },
  { name: 'shadow-sm', ...LIFT },
  { name: 'shadow-md', ...LIFT },
  { name: 'shadow-lg', ...LIFT },
  // The roles (theme-architecture.md 4.2), by the part of the surface each
  // paints. The grounds and the cards on them.
  { name: 'canvas', ...role('surface'), fallback: 'background' },
  { name: 'content', ...role('surface'), fallback: 'background' },
  { name: 'card-edge', ...role('surface') },
  { name: 'card-shadow', ...role('surface'), kind: 'shadow' },
  { name: 'scrim', ...role('surface') },
  // The tables: the header band, the totals band and the rows' states.
  { name: 'table-header', ...role('table'), fallback: 'muted' },
  {
    name: 'table-header-foreground',
    ...role('table'),
    fallback: 'foreground',
  },
  {
    name: 'table-header-weight',
    ...measure('table', 'number'),
    fallback: 'strong-weight',
  },
  { name: 'table-header-divider', ...role('table') },
  { name: 'totals', ...role('table'), fallback: 'muted' },
  { name: 'row-selected', ...role('table'), fallback: 'muted', brand: true },
  {
    name: 'row-selected-foreground',
    ...role('table'),
    fallback: 'foreground',
  },
  { name: 'row-hover', ...role('table') },
  { name: 'row-stripe', ...role('table'), fallback: 'content' },
  // What marks a state: a highlighted item, the view on screen, a control
  // under the pointer or pressed.
  { name: 'highlight', ...role('state'), fallback: 'accent' },
  {
    name: 'highlight-foreground',
    ...role('state'),
    fallback: 'accent-foreground',
  },
  { name: 'highlight-link', ...link('state', 'highlight', 'primary') },
  {
    name: 'highlight-foreground-link',
    ...link('state', 'highlight-foreground', 'primary-foreground'),
  },
  // The item a menu or a select holds chosen, under the pointer or not.
  { name: 'item-selected', ...role('state') },
  {
    name: 'item-selected-foreground',
    ...role('state'),
    fallback: 'popover-foreground',
  },
  { name: 'item-selected-weight', ...measure('state', 'number') },
  {
    name: 'item-selected-link',
    ...link('state', 'item-selected', 'row-selected'),
  },
  { name: 'nav-current', ...role('state'), fallback: 'background' },
  {
    name: 'nav-current-foreground',
    ...role('state'),
    fallback: 'foreground',
  },
  { name: 'nav-current-edge', ...role('state'), fallback: 'border' },
  { name: 'nav-current-shadow', ...role('state'), kind: 'shadow' },
  {
    name: 'nav-current-link',
    ...link('state', 'nav-current', 'row-selected'),
  },
  {
    name: 'nav-current-foreground-link',
    ...link('state', 'nav-current-foreground', 'primary'),
  },
  { name: 'control-hover', ...role('state') },
  { name: 'control-pressed', ...role('state') },
  // An outline button under the pointer: its edge and its words.
  { name: 'outline-hover-edge', ...role('state') },
  {
    name: 'outline-hover-foreground',
    ...role('state'),
    fallback: 'foreground',
  },
  {
    name: 'outline-hover-edge-link',
    ...link('state', 'outline-hover-edge', 'primary'),
  },
  {
    name: 'outline-hover-foreground-link',
    ...link('state', 'outline-hover-foreground', 'primary'),
  },
  // Focus.
  { name: 'focus-width', ...measure('focus', 'length') },
  { name: 'focus-offset', ...measure('focus', 'length') },
  { name: 'focus-style', ...measure('focus', 'keyword') },
  { name: 'focus-halo', ...role('focus') },
  // The controls: their fill, edge and pressed thumb, their heights, the
  // width of their edge, and a toned badge's wash.
  { name: 'control', ...role('control') },
  { name: 'control-edge', ...role('control') },
  { name: 'control-thumb', ...role('control') },
  { name: 'control-thumb-shadow', ...role('control'), kind: 'shadow' },
  { name: 'control-height', ...measure('control', 'length') },
  { name: 'control-height-sm', ...measure('control', 'length') },
  { name: 'filter-height', ...measure('control', 'length') },
  { name: 'edge-width', ...measure('control', 'length') },
  { name: 'badge-edge', ...measure('control', 'number') },
  { name: 'badge-fill', ...measure('control', 'number') },
  // The corners, part by part.
  { name: 'radius-card', ...measure('shape', 'length') },
  {
    name: 'radius-control',
    ...measure('shape', 'length'),
    fallback: 'radius',
  },
  {
    name: 'radius-popover',
    ...measure('shape', 'length'),
    fallback: 'radius',
  },
  { name: 'radius-badge', ...measure('shape', 'length') },
  { name: 'radius-checkbox', ...measure('shape', 'length') },
  // The type's weights.
  { name: 'title-weight', ...measure('type', 'number') },
  { name: 'strong-weight', ...measure('type', 'number') },
  // What floats over the rest.
  { name: 'tooltip', ...role('float'), fallback: 'foreground' },
  { name: 'tooltip-foreground', ...role('float'), fallback: 'background' },
  // The charts (theme-architecture.md 6): the library draws off the
  // cascade, so each is read back off the chart's element (`readChartTheme`)
  // — a colour, a length or a number, whichever the browser computes it to.
  // Unset, each is what the charts were drawn with before it existed.
  { name: 'chart-grid', ...role('chart'), fallback: 'border', chart: true },
  { name: 'chart-grid-width', ...measure('chart', 'length'), chart: true },
  {
    name: 'chart-axis',
    ...role('chart'),
    fallback: 'muted-foreground',
    chart: true,
  },
  { name: 'chart-text-size', ...measure('chart', 'length'), chart: true },
  { name: 'chart-label-size', ...measure('chart', 'length'), chart: true },
  { name: 'chart-line-width', ...measure('chart', 'length'), chart: true },
  { name: 'chart-area-opacity', ...measure('chart', 'number'), chart: true },
  { name: 'chart-bar-radius', ...measure('chart', 'length'), chart: true },
  { name: 'chart-bar-min-width', ...measure('chart', 'length'), chart: true },
  { name: 'chart-bar-max-width', ...measure('chart', 'length'), chart: true },
  { name: 'chart-slice-border', ...measure('chart', 'length'), chart: true },
  // The chart's tooltip is HTML inside the chart's element, so it reads its
  // roles through utilities (`charts/tooltip.ts`), as any popup does.
  { name: 'chart-tooltip', ...role('chart'), fallback: 'popover' },
  {
    name: 'chart-tooltip-foreground',
    ...role('chart'),
    fallback: 'popover-foreground',
  },
  {
    name: 'chart-tooltip-shadow',
    ...role('chart'),
    kind: 'shadow',
    fallback: 'shadow-md',
  },
  // The density's lengths (themes.md 2.4): the step gives each its default,
  // and a host's own value wins over the step. A preset only recommends the
  // step (`preset-density`) and writes none of them (theme-architecture.md
  // 4.6).
  { name: 'table-header-height', ...LAYOUT, kind: 'length' },
  { name: 'table-cell-padding-block', ...LAYOUT, kind: 'length' },
  { name: 'table-cell-padding-inline', ...LAYOUT, kind: 'length' },
  { name: 'sidebar-item-height', ...LAYOUT, kind: 'length' },
  { name: 'panel-padding', ...LAYOUT, kind: 'length' },
  { name: 'expanded-z-index', ...LAYOUT, kind: 'number' },
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

/** The variables one entry is written through in a layer, light first. */
const layer = (prefix: string, entry: TokenEntry) =>
  entry.modes === 2
    ? [`${prefix}${entry.name}`, `${prefix}dark-${entry.name}`]
    : [`${prefix}${entry.name}`];

/** The host variables one entry is written through, light first. */
export function hostVariables(entry: TokenEntry): string[] {
  return layer('--fve-', entry);
}

/**
 * The preset variables one entry is written through, light first — none for
 * an entry no preset owns.
 */
export function presetVariables(entry: TokenEntry): string[] {
  return entry.preset ? layer('--fvp-', entry) : [];
}

/**
 * The variable a token block declares an entry as: `--<name>` for shadcn's
 * names, `--_fve-<name>` for the engine's own; `undefined` for one read where
 * it is used.
 */
export function declaredVariable(entry: TokenEntry): string | undefined {
  if (!entry.block) return undefined;
  return entry.own ? `--_fve-${entry.name}` : `--${entry.name}`;
}

/**
 * The attributes, on a surface or an ancestor, that move what the tokens
 * resolve to, by the axis each carries (theme-architecture.md 1): the mode
 * (`class` for `.dark`, and any class a host themes by; `data-theme`, a
 * pinned mode), the preset, the change convention, the density — which
 * moves no colour, but a chart's cell, and so is watched too — and whether
 * the first chart slot follows the brand colour.
 */
export const THEME_AXES = [
  { name: 'mode', attributes: ['class', 'data-theme'] },
  { name: 'preset', attributes: ['data-fve-preset'] },
  { name: 'change-colors', attributes: ['data-fve-change-colors'] },
  { name: 'density', attributes: ['data-fve-density'] },
  // Present, the first chart slot takes the brand's hue (theme-architecture.md
  // 2, S4); absent, it is the preset's.
  { name: 'brand-chart', attributes: ['data-fve-brand-chart'] },
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
 * a token as the blocks declare it (`--chart-1`, `--_fve-rise`), and one
 * read where it is used by its host variable and then its preset variable,
 * the order the stylesheet reads the layers in (`--fve-chart-patterns`,
 * `--fvp-chart-patterns`).
 */
export const CHART_TOKENS: readonly string[] = (() => {
  const read: readonly TokenEntry[] = TOKENS.filter(
    (entry: TokenEntry) => entry.chart,
  );
  const slot = (entry: TokenEntry) => entry.group === 'chart';
  return [...read.filter(slot), ...read.filter(entry => !slot(entry))].flatMap(
    entry => {
      const declared = declaredVariable(entry);
      return declared
        ? [declared]
        : [`--fve-${entry.name}`, ...presetVariables(entry)];
    },
  );
})();
