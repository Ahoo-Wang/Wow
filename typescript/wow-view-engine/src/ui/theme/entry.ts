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
 * How an entry of the theme's registry is written: its shape, and the
 * templates the entries of `tokens.ts` are spread from — a colour of
 * shadcn's, one of the engine's own, a chart slot, a lift, a role, a link, a
 * bound, a host length. Which entries there are is `tokens.ts`'s; this file
 * says only what one may say (ui/theme.md「登记表：合同只有一个来源」).
 */

/**
 * Who a token is for, by what it describes (ui/theme.md「登记表：合同只有一个来源」):
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
 * `controls`, `title`, D43) are roles since S3 (ui/theme.md「角色：引擎自己的面」).
 */
export type TokenGroup =
  'chart' | 'shadow' | 'font' | 'patterns' | 'density' | 'brand';

/**
 * Which part of the surface a role paints (ui/theme.md「角色：引擎自己的面」): the
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
   * the host's that happens to share it (ui/theme.md「三层变量」).
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
   * (ui/theme.md「品牌色是输入，不是预设」, S4): the block reads
   * `var(--fve-<name>, var(--_fve-brand-<name>, var(--fvp-<name>, …)))`, so
   * the host's own value beats the brand and the brand beats the preset's
   * literal. With no brand colour the derived value is invalid and the
   * preset's literal is read, as before.
   */
  readonly brand?: boolean;
  /**
   * A link (ui/theme.md「链接：角色跟着面上解析出的 token」): this token says how much of a token
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
  /**
   * The height of something a reader presses — a control, a filter's chip,
   * a view in the list — held to `TARGET_FLOOR` (`pairs.ts`, WCAG 2.5.8):
   * theme-check reports a value under it as an error.
   */
  readonly target?: boolean;
}

/**
 * Each optional group, and whether a preset has to give it whole. Only a
 * set whose members are one design has to be: the eight chart slots are
 * measured against each other for colour-vision distance, and the three
 * lifts are one ladder. Any other member a preset leaves out is the
 * built-in value, since the reset rule clears what an outer preset gave
 * (ui/theme.md「三层变量」).
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
export const SHADCN = {
  tier: 'semantic',
  kind: 'color',
  modes: 2,
  block: true,
  preset: true,
  bridge: true,
} as const;

/** A colour of both modes a preset sets and the bridge leaves alone. */
export const OWN = { ...SHADCN, bridge: false } as const;

/** One of those under the engine's own name, `--_fve-<name>`. */
export const ENGINE = { ...OWN, own: true } as const;

/** One of the eight chart slots. */
export const SLOT = {
  ...OWN,
  tier: 'group',
  group: 'chart',
  chart: true,
} as const;

/** One of the three lifts. */
export const LIFT = {
  ...OWN,
  tier: 'group',
  kind: 'shadow',
  group: 'shadow',
} as const;

/**
 * A role (ui/theme.md「角色：引擎自己的面」): one surface of the engine's own, under
 * the engine's name, which a host and a preset may both set. Unset it is
 * the token named as its `fallback`, or the value drawn before the role
 * existed, so a theme that sets no role looks as it did.
 */
export const role = (area: RoleArea) =>
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
export const measure = (
  area: RoleArea,
  kind: 'length' | 'number' | 'keyword',
) => ({ ...role(area), kind, modes: 1 }) as const;

/**
 * One of the bounds a preset holds a brand colour to
 * (ui/theme.md「品牌色是输入，不是预设」): numbers the derivation in
 * `styles.css` reads, measured against the preset's own grounds. Unset, the stylesheet's own bound applies.
 */
export const BOUND = {
  tier: 'group',
  kind: 'number',
  modes: 2,
  preset: true,
  group: 'brand',
} as const;

/**
 * A link (ui/theme.md「链接：角色跟着面上解析出的 token」): the share of the resolved `to` a
 * colour role is drawn in, one number for both modes — `to` resolves in
 * each. No built-in value: unset, the role is what it was.
 */
export const link = (area: RoleArea, linked: string, to: string) =>
  ({
    ...role(area),
    kind: 'number',
    modes: 1,
    link: { role: linked, to },
  }) as const;

/** A host length or level, read where it is used. */
export const LAYOUT = { tier: 'layout', modes: 1 } as const;
