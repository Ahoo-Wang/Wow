---
title: Theming the View Engine
description: How the unreleased wow-view-engine takes a host's look — three layers of variables, presets, a brand colour as an input, the engine's roles and links, the charts' roles, light, dark and system mode, pinning, embeds and popups, the shadcn bridge, and the contrast an override owes.
---

# Theming the View Engine

::: warning Not released
`@ahoo-wang/wow-view-engine` has not been published to npm and carries no compatibility promise. This page describes theming as it stands in the repository.
:::

The theme is the host's look, not a way of observing: nothing about it is saved in a view, a dashboard or a preference, and the workbench has no theme switch. The host picks a preset, a brand colour and a mode; the engine follows. Everything below is CSS custom properties — there is no theme object.

## Start here: two paths

A host makes one choice, on `ViewHost` (the package README's quick start):

| Path                   | For                                                 | Write                                                                              |
| ---------------------- | --------------------------------------------------- | ---------------------------------------------------------------------------------- |
| The engine follows you | A host with a shadcn theme (Tailwind v4)            | `theme="host"`, and import `shadcn-bridge.css` ([the bridge](#the-shadcn-bridge))  |
| You follow the engine  | A host with no theme, or happy to wear the engine's | `preset="porcelain"` (import its file), optionally `brand="#1d4ed8"` ([presets](#presets), [a brand colour](#i-have-a-brand-colour)); your own chrome wears [`fve-tokens`](#your-own-chrome-fve-tokens) |

`ViewHost` names the preset and the brand on `<html>` and paints light or dark there (`colorMode`, [below](#light-dark-and-system)). The rest of this page is what those two lines stand on, and what to reach for past them.

## Stylesheets

| Entry                                          | What it is                                                                                           | Import it when                      |
| ---------------------------------------------- | ---------------------------------------------------------------------------------------------------- | ----------------------------------- |
| `@ahoo-wang/wow-view-engine/styles.css`        | The theme: every rule scoped inside the view's own boundary, every token reading your variable first | Always                              |
| `@ahoo-wang/wow-view-engine/themes.css`        | The built-in presets, keyed by a `data-fve-preset` attribute                                         | You switch presets at run time      |
| `@ahoo-wang/wow-view-engine/themes/<name>.css` | One built-in preset alone, the same block `themes.css` holds for it                                  | You wear one preset                 |
| `@ahoo-wang/wow-view-engine/shadcn-bridge.css` | Your shadcn/ui (Tailwind v4) tokens read into the preset layer                                       | Your app already has a shadcn theme |

The optional files only assign preset variables (`--fvp-*`): they paint nothing and never touch a variable of yours. The package's build checks that on every release.

## Three layers

Three parties write the theme, each under a prefix of its own, and every token reads them in one fixed order:

| Prefix                    | Who writes it                                                 | Where                                                    | Examples                                                     |
| ------------------------- | ------------------------------------------------------------- | -------------------------------------------------------- | ------------------------------------------------------------ |
| `--fve-*`, `--fve-dark-*` | **You**, the host                                             | `:root`, any ancestor of a view, or a surface's `tokens` | `--fve-primary`, `--fve-brand`, `--fve-row-selected`         |
| `--fvp-*`, `--fvp-dark-*` | A **preset** — a built-in one, your own, or the shadcn bridge | a `:where([data-fve-preset='…'])` block                  | `--fvp-primary`, `--fvp-brand-l-max`, `--fvp-highlight-link` |
| `--_fve-*`                | The **engine**, for itself                                    | inside the view                                          | what it resolves and measures; never yours to write or read  |

Each token of the surface is `var(--fve-<token>, var(--fvp-<token>, <built-in>))`: yours first, then the preset's, then the stylesheet's own value. So **a variable you set wins over any preset**, the one on `<html>` and one pinned on a surface alike, whichever stylesheet loads first — override one colour of a preset without restating the rest. To keep one surface out of an override, write the override on a narrower selector than `:root`.

A `--_fve-*` name is the engine's own and changes without notice; a `--fve-*` or `--fvp-*` name the registry does not list does nothing. The full list is the [token table](#every-variable) below, generated from the theme's registry, and `dist/theme-tokens.json` is that registry as data.

## Presets

Picking a look is one line: import the preset's file and name it on `<html>`.

```ts
import '@ahoo-wang/wow-view-engine/styles.css';
import '@ahoo-wang/wow-view-engine/themes/porcelain.css';
```

```html
<html data-fve-preset="porcelain"></html>
```

Import `themes.css` instead to have every preset and switch at run time. The attribute on `<html>` reaches every view and every popup; to give one view its own, pass `preset` (see [Pinning a preset](#pinning-a-preset)). `/ui` exports `BUILT_IN_PRESETS`, the list of built-in names, for a picker in your own chrome — the engine draws none.

| Preset      | Character                                                                                                                          | Corners          | Chart colours |
| ----------- | ---------------------------------------------------------------------------------------------------------------------------------- | ---------------- | ------------- |
| `neutral`   | The default: neutral greys, a black primary                                                                                        | 10px             | default       |
| `azure`     | Chinese enterprise admin: a clear blue, white rows on a grey page, a type stack with the Chinese faces first                       | 6px              | its own       |
| `porcelain` | Native desktop: system type, 6px controls on 12px cards, soft shadows, near-neutral greys, a filled menu highlight, striped tables | 6px / 12px cards | its own       |
| `contrast`  | High contrast: text at 7:1, 2px edges and a 2px focus ring 2px off the control at 4.5:1, a tinted selected row, chart patterns on  | 4px              | its own       |

Which one fits your brand:

| Your situation                                                     | Use                                                                                                   |
| ------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------- |
| Your app is a shadcn app with its own theme                        | [`shadcn-bridge.css`](#the-shadcn-bridge), no preset                                                  |
| No design system, and you want a ready look                        | The preset above closest to your product                                                              |
| A back office in the style of the open-source kits common in China | `azure`, with `data-fve-change-colors="red-up"` on boards read by mainland-China markets              |
| A macOS / Apple desktop-app feel                                   | `porcelain`                                                                                           |
| Only a brand colour                                                | `--fve-brand` on any preset, `neutral` included (see [I have a brand colour](#i-have-a-brand-colour)) |
| A full design specification                                        | The closest preset, then override the few `--fve-*` that differ on `:root`                            |

Each preset gives both a light and a dark half, measured pair by pair: text at 4.5:1, a control's edge and the focus mark at 3:1, and a palette of its own through the same colour-vision gates as the default eight. The values each one sets, and why, are in the package's `src/themes/<name>.css`.

- **A preset and the mode are independent.** The preset supplies both halves of the values; light or dark is still decided as described under [Light, dark and system](#light-dark-and-system).
- **A preset writes only what it changes.** Whatever it leaves out is the stylesheet's own value: `styles.css` empties the preset layer on every element that names a preset (a zero-weight rule in its lowest layer, `fve-reset`), so a preset pinned inside another takes nothing of the outer one's, and `neutral` writes nothing at all.
- **What a preset may give**: the colours and `radius` it changes, any of the [roles](#roles) and [links](#role-links), the bounds a [brand colour](#i-have-a-brand-colour) is held to (`--fvp-brand-l-min` and the rest), its own eight chart colours and three shadows (each set whole, both modes, or not at all), a system font stack (`--fvp-font-sans`), the chart patterns' pin (`--fvp-chart-patterns`, which only `contrast` sets) and the density it recommends (`--fvp-preset-density`). It never sets `pin-shadow`, `text-ui`, the rise and fall colours or the brand colour itself.

### Your own preset

Your own preset is written the same way and selected by the same attribute or prop. The built-in presets use this same contract and nothing else — no private selector, no code path for one preset — so what they do, yours can do:

```css
:where([data-fve-preset='acme']) {
  --fvp-radius: 0.5rem;
  --fvp-table-header-weight: 600;
  --fvp-highlight-link: 100%;
  --fvp-highlight-foreground-link: 100%;
  --fvp-focus-width: 2px;
  --fvp-focus-offset: 2px;
  --fvp-focus-halo: transparent;
  --fvp-dark-focus-halo: transparent;
}
```

- **Outside any `@layer` of yours.** The reset sits in the lowest layer of `styles.css`; a preset inside a layer your stylesheet declared before it loses to the reset and paints nothing.
- **`--fvp-*` only, and only what differs.** No `initial`, no copy of the values you keep. A `--fve-*` in a preset block is a host variable: every preset pinned inside that element would lose to it.
- **Check it.** `wow-view-engine theme-check` holds it to the registry and every contrast pair in your CI (see [Checking a theme](#checking-a-theme)); paste its declarations into the [contrast matrix](/storybook/?path=/story/view-engine-能力-主题与预设--contrast): they are measured beside the built-in presets, pair by pair, and its chart colours through the palette gates.

The Storybook page [A host's own theme](/storybook/?path=/story/view-engine-能力-主题与预设-宿主自定义主题--host-authored) is a complete example, `stories/view-engine/host-theme/acme.css` in the repository: a stylesheet outside the package with a brand colour, a few roles, a linked menu highlight and a palette of its own, held to the same gates in the browser and in the package's tests.

## I have a brand colour

A brand colour is not a preset: give it as `--fve-brand` and wear whichever preset you like — or none.

```css
@import '@ahoo-wang/wow-view-engine/styles.css';
@import '@ahoo-wang/wow-view-engine/themes/azure.css';

:root {
  --fve-brand: #7c3aed;
  --fve-dark-brand: #a78bfa;
}
```

```html
<html data-fve-preset="azure"></html>
```

The primary takes your colour's hue, and so do faint tints of the selected item (`accent`), the hovered view in the list (`sidebar-accent`) and a selected row (`row-selected`); where a preset's focus ring is its primary (`porcelain`, `contrast`) the ring follows too. The colours are derived in OKLCH, once, in `styles.css`, on the view itself, and each preset only gives the **bounds** it holds them to on its own grounds: `neutral` clamps the primary's lightness to 0.40–0.50 in light and 0.68–0.80 in dark, `contrast` to 0.25–0.36 and 0.80–0.90 for its 7:1. So any colour holds every contrast line of the preset you wear — a test sweeps the whole sRGB range on every preset, in both modes — and a very light or very dark brand comes out deeper or lighter than its book. The greys, `input`, the status colours and the chart palette stay the preset's.

- `--fve-dark-brand` gives the dark half its own colour; left out, dark derives from `--fve-brand`.
- Put the variable anywhere above the view — `:root`, a wrapper, or a surface's `tokens` (popups leave a wrapper, so for one view use `tokens`).
- A `--fve-primary` (or `--fve-accent`, `--fve-sidebar-accent`, `--fve-row-selected`, `--fve-ring`) you set still wins over the brand, which wins over the preset's own colour.
- The bounds are preset variables (`--fvp-brand-l-min`, `--fvp-brand-l-max`, `--fvp-brand-c-max` and the rest of the `brand-*` rows of the token table). You may widen or narrow one as `--fve-brand-l-max` and so on; then its measurement is yours.
- The first chart colour stays the preset's unless you put `data-fve-brand-chart` on `<html>` (or any ancestor of the view): then it takes your hue at the lightness and chroma the preset tuned it to, and measuring the palette is yours, as when you set `--fve-chart-1`. It is an attribute, like the preset and the density: present is on, absent is off, and a view copies it onto its popups.
- Roles [linked](#role-links) to the primary or the selected row follow the brand as well: `porcelain`'s menu highlight, `azure`'s current view and chosen item.
- Without a colour, or in a browser older than Chrome 119, Safari 18 or Firefox 128, the preset is exactly as it ships.

## Host overrides

Every token reads a host variable first: `--fve-<token>` for light and `--fve-dark-<token>` for dark. Set them on your `:root`:

```css
:root {
  --fve-primary: oklch(0.4 0.21 265deg);
  --fve-primary-foreground: oklch(0.99 0 0deg);
  --fve-dark-primary: oklch(0.75 0.15 265deg);
  --fve-dark-primary-foreground: oklch(0.21 0.05 265deg);
  --fve-radius: 0.375rem;
}
```

They win over the preset you chose and over one pinned on a surface, as [Three layers](#three-layers) says — so they are measured on every preset: this primary is dark enough for `contrast`'s 7:1 as well as the 4.5:1 of the rest, and [`theme-check`](#checking-a-theme) measures yours the same way. For one surface alone, pass `tokens` to `ViewSurface`, a workbench or an embed rather than setting the variables on a wrapper: a popup is portalled to `<body>`, out from under the wrapper, and `tokens` is written on the surface and on every popup it opens. Its type, `FveToken` from `/ui`, is every host variable of the registry.

One token does not follow your primary on its own: `primary-fill`, the primary as a fill under words (a menu's highlighted item). A preset may give it a deeper step where its primary is too light to carry white words — `porcelain` does in dark, as a fixed colour — so on such a preset set it beside your dark primary, measured under its words like any pair:

```css
:root {
  --fve-dark-primary-fill: oklch(0.44 0.2 265deg);
  --fve-dark-primary-fill-foreground: oklch(0.99 0 0deg);
}
```

A brand colour (`--fve-brand`) moves that step itself; only a primary you write outright needs it.

<!-- typecheck-context
import type { ViewEngine } from '@ahoo-wang/wow-view-engine';
import { DataWorkbench } from '@ahoo-wang/wow-view-engine/ui';
declare const engine: ViewEngine;
-->

```tsx
<DataWorkbench
  engine={engine}
  definitionId="orders"
  tokens={{ '--fve-brand': '#0f766e', '--fve-table-header-weight': '600' }}
/>
```

## Roles

A token like `muted` is a kind of colour, and the surface uses it in several places: the header band, the totals band and a selected row are all `muted`. A **role** is one of those places — one surface of the engine's own — so you can change it alone. Each is a variable like every token (`--fve-<role>` for you, `--fvp-<role>` for a preset, `-dark-` for a colour's dark half) and, unset, falls back to the token it always read, or to what the surface drew before the role existed: setting no role changes nothing, and moving `--fve-muted` still moves the bands and the selection together.

```css
:root {
  --fve-row-selected: oklch(0.96 0.03 250deg);
  --fve-table-header-weight: 600;
  --fve-table-header-divider: oklch(0.87 0 0deg);
  --fve-focus-width: 2px;
  --fve-focus-offset: 2px;
  --fve-focus-halo: transparent;
  --fve-dark-focus-halo: transparent;
  --fve-control-height: 2.25rem;
  --fve-control-height-sm: 1.875rem;
}
```

That tints the selection and leaves the header band `muted`, sets the header at 600 with a line between its columns, trades the focus halo for a 2px outline 2px off the control, and makes the controls 36px and 30px tall.

| Part of the surface | Roles                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| ------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Grounds and cards   | `canvas` (a board's grouped ground), `content` (the rows' ground), `card-edge`, `card-shadow`, `scrim`                                                                                                                                                                                                                                                                                                                                               |
| Tables              | `table-header`, `table-header-foreground`, `table-header-weight`, `table-header-divider`, `totals`, `row-selected`, `row-selected-foreground`, `row-selected-mark` (a bar down a selected row's left edge, none unless set), `row-hover`, `row-stripe` (off unless set), `row-divider` (the line between body rows, `border` unless set), `table-sort-idle` (how much an idle sort mark shows where a pointer hovers, 1 unless set)                                                                                                                                                                                                                                                            |
| States              | `highlight`, `highlight-foreground` (the item a menu, a select or a combobox has under the keyboard); `item-selected`, `item-selected-foreground`, `item-selected-weight` (the item it holds chosen); `nav-current`, `nav-current-foreground`, `nav-current-edge`, `nav-current-shadow` (the view on screen in the list); `control-hover`, `control-pressed`; `outline-hover-edge`, `outline-hover-foreground` (an outline button under the pointer) |
| Focus               | `focus-width`, `focus-offset`, `focus-style`, `focus-halo`                                                                                                                                                                                                                                                                                                                                                                                           |
| Controls            | `control`, `control-edge`, `control-thumb`, `control-thumb-shadow`, `control-height`, `control-height-sm`, `filter-height` (a board's filter chip), `edge-width`, `badge-edge`, `badge-fill`                                                                                                                                                                                                                                                         |
| Corners             | `radius-card`, `radius-control`, `radius-popover`, `radius-badge`, `radius-checkbox`                                                                                                                                                                                                                                                                                                                                                                 |
| Type and tooltips   | `title-weight`, `strong-weight`, `tooltip`, `tooltip-foreground`                                                                                                                                                                                                                                                                                                                                                                                     |
| Charts              | see [The charts' roles](#the-charts-roles)                                                                                                                                                                                                                                                                                                                                                                                                           |

- **The lines a role owes**: a control stays 24px tall or more at either height (WCAG 2.5.8) — `theme-check` reports `--fve-control-height`, `--fve-control-height-sm` or `--fve-filter-height` under it as an error, since the stylesheet takes the height you write as written — and a theme promising AAA text gives its focus outline 2px or more (WCAG 2.4.13). Every role that is a ground is in the contrast matrix like the rest, so a theme that parts it from its token is measured on what it paints.
- **The words and defaults** for each role are in the [token table](#every-variable).

### Role links

A colour a preset writes is fixed where the preset is named — usually `<html>` — so a preset cannot say "the menu highlight is the primary": that primary is resolved later, on the view, from your brand colour or your own `--fve-primary`. A **link** says it instead. `<role>-link` is a share of another token the view has resolved, `100%` being that token itself and less a translucent wash of it over the ground:

| Link                            | Draws                      | In                        |
| ------------------------------- | -------------------------- | ------------------------- |
| `highlight-link`                | `highlight`                | `primary-fill`            |
| `highlight-foreground-link`     | `highlight-foreground`     | `primary-fill-foreground` |
| `item-selected-link`            | `item-selected`            | `row-selected`            |
| `nav-current-link`              | `nav-current`              | `row-selected`            |
| `nav-current-foreground-link`   | `nav-current-foreground`   | `primary`                 |
| `outline-hover-edge-link`       | `outline-hover-edge`       | `primary`                 |
| `outline-hover-foreground-link` | `outline-hover-foreground` | `primary`                 |
| `row-selected-mark-link`        | `row-selected-mark`        | `primary`                 |

```css
:root {
  --fve-highlight-link: 100%;
  --fve-highlight-foreground-link: 100%;
}
```

With those two, every menu, select and combobox highlights its item with the primary's fill and ink — your brand's, in either mode. `primary-fill` is the primary itself unless a preset gives a deeper step where the primary is too light to carry white words (`porcelain`'s dark, which bounds it so a brand colour still follows; with your own `--fve-dark-primary` on it, set `--fve-dark-primary-fill` too, as [Host overrides](#host-overrides) shows). A link is one number for both modes, since its target resolves in each. A role colour you set wins over a link, and a link, yours or a preset's, wins over the preset's own colour; unset, the role is what it was. `porcelain` and `contrast` link their menu highlight, `contrast` its selected row's bar, and `azure` its current view, its chosen item and an outline button's hovered edge — which is why they follow a brand colour.

## The charts' roles

The chart library draws its own SVG, which no stylesheet reaches, so a chart reads its whole look back off its element — a colour as a colour, a length in pixels, a number as a number, each worked out by the browser (`calc()`, `min()` and `oklch(from …)` included) — and is drawn in that:

| Role                                                                | What                                                                | Unset                                        |
| ------------------------------------------------------------------- | ------------------------------------------------------------------- | -------------------------------------------- |
| `chart-grid`, `chart-grid-width`                                    | Gridlines and the axes' rules                                       | `border`, 1px                                |
| `chart-axis`                                                        | The chart's quiet text: ticks, axis names, a scale's ends           | `muted-foreground`                           |
| `chart-text-size`, `chart-label-size`                               | The chart's text and a value label                                  | `text-ui` less 1px and 2px                   |
| `chart-line-width`, `chart-area-opacity`                            | A line, and an area under it                                        | 2px, 0.2                                     |
| `chart-bar-radius`, `chart-bar-min-width`, `chart-bar-max-width`    | A bar's corner and the bounds of its width                          | 0.6 of `radius` up to 2px; none; 80px        |
| `chart-slice-border`                                                | The seam between two slices                                         | 1px                                          |
| `chart-map-edge` | A map's boundaries, round every area: what tells an area with no number, an island or a disputed line from the ground; an edge, 3:1 on the ground and on the palest shade | `chart-axis` |
| `chart-tooltip`, `chart-tooltip-foreground`, `chart-tooltip-shadow` | The chart's tooltip, which is HTML and reads them as any popup does | `popover`, `popover-foreground`, `shadow-md` |

A bar's corner follows `radius`, so a square style's bars are square with no word about bars. A chart is told to read them again when its theme moves (see [Embeds and popups](#embeds-and-popups)).

## Light, dark and system

| How                                                                         | Effect                                                                                                                                                             |
| --------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `ViewHost`, as it comes (`colorMode="system"`)                              | Paints `<html>`'s `.dark` and `color-scheme` before the first paint, following the system live; `useColorMode()` lets your switch pin one, kept under `rememberColorMode` |
| `colorMode="light"` or `"dark"` on `ViewHost`                               | Starts pinned; the reader may still pick another                                                                                                                   |
| `colorMode="host"` on `ViewHost`, and a `.dark` class of yours, usually on `<html>` | Views follow your page's mode (next-themes, a switch of your own); the engine leaves `<html>` alone                                                         |
| `theme="light"` or `theme="dark"` on `ViewSurface`, a workbench or an embed | Pins that one view                                                                                                                                                 |
| `theme="system"`                                                            | Follows the reader's `prefers-color-scheme`, live, for that one view                                                                                               |

**Dark values are `--fve-dark-*`, never `--fve-*` under `.dark`.** The shadcn habit — rewriting the same variable under `.dark` — does not survive pinning: variables inherit down from `<html class="dark">`, so a view pinned light on a dark page would be handed your dark value with nothing to tell it apart. Two halves, each named for its mode, let every surface pick its own. A host on the [bridge](#the-shadcn-bridge) is not affected: the bridge reads your shadcn tokens, which you switch under `.dark` as ever.

## Pinning a preset

`preset="porcelain"` on `ViewSurface`, a workbench or an embed pins that view to a preset, whatever `<html>` says. A `data-fve-preset` on any other ancestor works too: the surface finds the nearest one. A pinned preset replaces the outer one whole, and your own `--fve-*` still win inside it.

<!-- typecheck-context
import type { ViewEngine } from '@ahoo-wang/wow-view-engine';
import { EmbeddedView } from '@ahoo-wang/wow-view-engine/ui';
declare const engine: ViewEngine;
declare const id: string;
-->

```tsx
<EmbeddedView engine={engine} instanceId={id} theme="dark" preset="azure" />
```

## Embeds and popups

Menus, selects, popovers, tooltips and dialogs are portalled to `<body>`, outside the part of the page the view sits in. They carry what the surface resolved — its mode as `data-theme`, its preset as `data-fve-preset`, and the density, change convention and brand-chart switch it found — so a pinned embed's popups match it.

- **Use an attribute, not a stylesheet swap.** A chart reads its look off the tokens and is told to read it again when one of these attributes changes on the surface or an ancestor: `class`, `data-theme`, `data-fve-preset`, `data-fve-change-colors`, `data-fve-density`, `data-fve-brand-chart` or `style`. A stylesheet replaced with no attribute changing leaves charts in the old colours.
- **Variables set on one element stop at `<body>`.** `--fve-*` set on a card around an embed reach the embed but not its popups, which are not inside the card. Put page-wide values on `:root`; use `tokens` or `preset` for one view.
- **An embed on a card** paints `--background`. Give it the card's colour on the card — `--fve-background` and `--fve-dark-background` — rather than `transparent`.
- **Stacking**: popups paint at `z-index: 50`; raise them all with `--fve-popup-z-index` on `:root`.

## The shadcn bridge

```ts
import '@ahoo-wang/wow-view-engine/styles.css';
import '@ahoo-wang/wow-view-engine/shadcn-bridge.css';
```

The bridge writes the preset layer — each `--fvp-<token>` and `--fvp-dark-<token>` — from the shadcn token of the same name: `background`, `foreground`, `card`, `popover`, `primary`, `secondary`, `muted`, `accent` with their `-foreground` pairs, `border`, the five `sidebar*` tokens and `radius`, and `--fvp-font-sans` from your `--font-sans`. It is resolved on `<html>`, so it takes your values in the mode `<html>` is in, and your own `--fve-*` still win over it.

| Not bridged                         | Why                                                                                                                                                                                     |
| ----------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `input`, `ring`                     | A shadcn theme often writes `--input: var(--border)` and `--ring: var(--primary)`: a divider grey and a brand colour that owe nothing of the 3:1 a control's edge and a focus mark need |
| `destructive`, `success`, `warning` | Text colours measured to 4.5:1 in both modes; shadcn has no `success` or `warning`                                                                                                      |
| The eight chart colours             | shadcn palettes have five, often starting on red; these are measured for colour-vision distance                                                                                         |
| The shadows                         | shadcn has no standard name for them                                                                                                                                                    |
| `row-hover`, `quiet-foreground`     | Derived from bridged tokens                                                                                                                                                             |

To adopt it in an app with an existing shadcn theme:

1. Keep your `:root` and `.dark` blocks as they are, with `.dark` on `<html>`.
2. Import `styles.css` and `shadcn-bridge.css`. Drop any `data-fve-preset` on `<html>`: the bridge applies only while `<html>` names no preset, so a preset there wins, and a surface pinned with `preset` wears that preset instead.
3. Let views follow your page's mode. A view pinned to the opposite mode would read your current values in both halves.
4. Override what you want beyond the bridge one variable at a time, for example `--fve-ring`, and measure it (below).
5. Check your own text tokens: they are carried across as they are. A `--muted-foreground` that misses 4.5:1 on your `--background` misses it in the views too.

### Tailwind v4 only

The bridge reads your tokens as colours, as a Tailwind v4 shadcn theme writes them (`--primary: oklch(0.205 0 0)`). A Tailwind v3 theme writes HSL channels instead (`--primary: 222.2 47.4% 11.2%`), which are no colour on their own: bridged as they are, every one of them is invalid. For a v3 app, skip `shadcn-bridge.css` and write the same rule yourself, wrapping each channel token in `hsl()`:

```css
:where(:root:not([data-fve-preset])) {
  --fvp-background: hsl(var(--background));
  --fvp-dark-background: hsl(var(--background));
  --fvp-foreground: hsl(var(--foreground));
  --fvp-dark-foreground: hsl(var(--foreground));
  --fvp-primary: hsl(var(--primary));
  --fvp-dark-primary: hsl(var(--primary));
  --fvp-radius: var(--radius);
}
```

One line for each token the bridge covers, and its dark half, as `shadcn-bridge.css` lists them. A Storybook regression test (`ShadcnBridge.test.stories.tsx`) hangs the compensation console's shadcn theme on a workbench with the bridge and measures its control edges and focus in both modes.

## Contrast is the overrider's responsibility

Every built-in preset holds these lines in both modes, on every pair the surface paints — ink on its ground, an edge on what is behind it — measured in jsdom by the package's tests and in a real browser by the contrast matrix. The pairs are the package's list, `src/ui/theme/pairs.ts`, and every role that is a ground is on it.

| Line                                                         | What                                                                                                                                                                                                                                                                              |
| ------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Text, ≥4.5:1 (7:1 for `contrast`)                            | Every foreground on its ground: the page, cards, popovers, the header and totals bands, a selected, striped or hovered row, a highlighted or chosen item, the current view, a tooltip, the chart's quiet text; the status colours as text, and as a badge's words on its own wash |
| Controls, focus and state marks, ≥3:1 (4.5:1 for `contrast`) | `input` and `ring` on every ground a control sits on, a dark control's own `input/30` wash included; `primary`, `rise` and `fall` where they fill a mark that carries a state                                                                                                     |
| None                                                         | `border` and `sidebar-border` (dividers), `radius`, `text-ui`                                                                                                                                                                                                                     |

When you set a colour — a token, a role, a link, a brand bound — you take over its line on every ground it lands on. `--fve-ring` and `--fve-input` (or their `--fve-dark-` halves) are the ones hosts most often lose: an unticked checkbox is only its `input` edge, and a focused control is known by its `ring` edge. A brand colour within the preset's bounds, and a role you leave unset, owe you nothing.

Measure a theme of your own in the Storybook [contrast matrix](/storybook/?path=/story/view-engine-能力-主题与预设--contrast): paste your `--fve-*` or `--fvp-*` declarations into its field and they are measured beside the built-in presets, pair by pair.

## Checking a theme

The package ships one command, for a host's CI: it holds your theme to the gates the built-in presets are held to, by the same registry and the same arithmetic the package's own tests use.

```bash
pnpm exec wow-view-engine theme-check src/theme.css
pnpm exec wow-view-engine theme-check src/theme.css --preset azure --preset porcelain
```

It reads your stylesheets (several are put together in the order given) and reports, with the line and column:

- **the registry and its layers**: a `--fve-*` or `--fvp-*` the registry does not list (it does nothing), a `--_fve-*` written or read (the engine's own), a preset variable outside a preset, a host variable inside one, a preset inside an `@layer` (the reset beats it), a chart palette or a shadow ladder given in part, `initial` in a preset;
- **a target's height**: `--fve-control-height`, `--fve-control-height-sm`, `--fve-filter-height` or `--fve-sidebar-item-height` (or their `--fvp-*` in your preset) under 24px, the least a thing a reader presses may be (WCAG 2.5.8) — an error, with the value to write; a height it cannot work out (a `calc()`) is a warning. The stylesheet takes the height you write as written and floors none of them;
- **Tailwind v3's HSL channels** — a colour written or read as `222.2 47.4% 11.2%`, or a v3 shadcn theme the bridge would read — with the `hsl()` it wants;
- **the brand's bounds** you move (`--fve-brand-*`, or `--fvp-brand-*` in your preset): out of range or crossed, and a sweep of brand colours across sRGB, since a bound is a promise for every colour;
- **contrast**: every pair of `src/ui/theme/pairs.ts`, in both modes and every change convention, on each preset of yours and on the built-in presets your `:root` variables are worn on (all of them unless you name some with `--preset`), a brand colour you give in both gamut mappings;
- **the chart palette**, when you bring one or pass `--brand-chart`: the three gates above.

It exits 1 on an error and 0 on warnings alone; `--json` prints the findings as data. It reads `dist/theme-tokens.json`, `dist/theme-source.css` (the token rules of `styles.css`, as written) and `dist/themes.css`, and runs in Node 22.12 or later, outside any page. What it cannot see is what your page does at run time — a variable set from script, a selector it does not know is yours — so the contrast matrix in Storybook stays the check of what a browser paints.

## Chart colours

The eight chart slots are tuned for colour-vision distance between neighbouring slots (the eighth beside the first) and for a legible label ink on every mark, in both modes, and are not bridged. A preset may bring its own eight — all sixteen, both modes, or none — held to the same gates. A slot is an ordinal, "the third series", not a hue: a chart that says `var(--chart-3)` changes colour with the preset, and what means good or bad, up or down, is written with a status colour or `--rise` / `--fall` instead. A host can still set `--fve-chart-1` … `--fve-chart-8` and their `--fve-dark-` halves; it then owes its palette those measurements. Colours saved in a chart's own config are declared in code.

## Rising and falling

A metric card's change and a waterfall's steps are coloured by the host's change convention, `data-fve-change-colors` on `<html>`:

| Value                | A metric card's change                  | A waterfall's rise / fall |
| -------------------- | --------------------------------------- | ------------------------- |
| unset, or `semantic` | good or bad (`success` / `destructive`) | `success` / `destructive` |
| `green-up`           | up or down                              | `success` / `destructive` |
| `red-up`             | up or down, red for up                  | `destructive` / `success` |

It is the host's call by market and reader — never switched by the interface language, never set by a preset, and not a prop, since one page reads one market. `--fve-rise` / `--fve-fall` set the colours themselves. The direction is never said by colour alone: the card's change carries an arrow and a sign, and a waterfall's labels are signed.

## Density

`data-fve-density` on `<html>` — `compact`, `default` or `comfortable` — sets how tightly tables, the view list and dashboard panels sit: a header row of 32, 40 or 44px, 6, 8 or 12px beside a value, views of 24, 28 or 32px, 8, 12 or 16px round a panel. Controls, type and the dashboard's 80px row never change. `density` on a surface pins one view. Without either, a surface sits where its preset recommends (`porcelain` comfortable), and at `default` it draws exactly what it drew before.

### One length of your own

Each of those lengths is a host variable as well, for a host whose tables or panels need one length the three steps do not give. Set one and it wins over the step — under any preset, pinned or not, and whatever `data-fve-density` says — while the step still gives the rest:

```css
:root {
  --fve-table-header-height: 36px;
  --fve-table-cell-padding-inline: 10px;
}
```

The five are `--fve-table-header-height`, `--fve-table-cell-padding-block` and `--fve-table-cell-padding-inline` (a table's header row, and the space above and below and either side of a value), `--fve-sidebar-item-height` (a view in the list) and `--fve-panel-padding` (round a dashboard panel's content; above and below it stops at 12px, so a tile one 80px row tall keeps room for its number). They are layout, not theme: no preset sets them, a preset only recommends a step. Nothing floors them either — a view in the list is a button, so keep `--fve-sidebar-item-height` at 24px or more (WCAG 2.5.8); `theme-check` reports it as an error under that.

## Every variable

`/ui` exports `FveToken`, the type of every variable in the table below — `--fve-<token>` and, where it has one, `--fve-dark-<token>` — for a host that sets them from code. The table is generated from the package's theme registry, and `dist/theme-tokens.json` is that registry as data, for a host's own tooling.

<!-- theme-tokens:begin -->

| Token                           | Role                                                                                                                                                                                                  | Light default                                         | Dark default                      |
| ------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------- | --------------------------------- |
| `background`                    | Surface behind everything                                                                                                                                                                             | `oklch(1 0 0deg)`                                     | `oklch(0.145 0 0deg)`             |
| `foreground`                    | Default text                                                                                                                                                                                          | `oklch(0.145 0 0deg)`                                 | `oklch(0.985 0 0deg)`             |
| `card`                          | Card and panel surface                                                                                                                                                                                | `oklch(1 0 0deg)`                                     | `oklch(0.205 0 0deg)`             |
| `card-foreground`               | Text on cards                                                                                                                                                                                         | `oklch(0.145 0 0deg)`                                 | `oklch(0.985 0 0deg)`             |
| `popover`                       | Popup surface                                                                                                                                                                                         | `oklch(1 0 0deg)`                                     | `oklch(0.205 0 0deg)`             |
| `popover-foreground`            | Text in popups                                                                                                                                                                                        | `oklch(0.145 0 0deg)`                                 | `oklch(0.985 0 0deg)`             |
| `primary`                       | Primary action fill                                                                                                                                                                                   | `oklch(0.205 0 0deg)`                                 | `oklch(0.922 0 0deg)`             |
| `primary-foreground`            | Text on primary                                                                                                                                                                                       | `oklch(0.985 0 0deg)`                                 | `oklch(0.205 0 0deg)`             |
| `secondary`                     | Secondary action fill                                                                                                                                                                                 | `oklch(0.97 0 0deg)`                                  | `oklch(0.269 0 0deg)`             |
| `secondary-foreground`          | Text on secondary                                                                                                                                                                                     | `oklch(0.205 0 0deg)`                                 | `oklch(0.985 0 0deg)`             |
| `muted`                         | Muted surface                                                                                                                                                                                         | `oklch(0.97 0 0deg)`                                  | `oklch(0.269 0 0deg)`             |
| `muted-foreground`              | Secondary text                                                                                                                                                                                        | `oklch(0.556 0 0deg)`                                 | `oklch(0.708 0 0deg)`             |
| `accent`                        | Hover and selected fill                                                                                                                                                                               | `oklch(0.97 0 0deg)`                                  | `oklch(0.269 0 0deg)`             |
| `accent-foreground`             | Text on accent                                                                                                                                                                                        | `oklch(0.205 0 0deg)`                                 | `oklch(0.985 0 0deg)`             |
| `sidebar`                       | Navigation column ground                                                                                                                                                                              | `oklch(0.97 0 0deg)`                                  | `oklch(0.205 0 0deg)`             |
| `sidebar-foreground`            | Text in the navigation column                                                                                                                                                                         | `oklch(0.145 0 0deg)`                                 | `oklch(0.985 0 0deg)`             |
| `sidebar-accent`                | Hovered row in the column                                                                                                                                                                             | `oklch(0.922 0 0deg)`                                 | `oklch(0.279 0 0deg)`             |
| `sidebar-accent-foreground`     | Text on a hovered row                                                                                                                                                                                 | `oklch(0.205 0 0deg)`                                 | `oklch(0.985 0 0deg)`             |
| `sidebar-border`                | The column's edge                                                                                                                                                                                     | `oklch(0.898 0 0deg)`                                 | `oklch(1 0 0deg / 20%)`           |
| `destructive`                   | Danger and delete                                                                                                                                                                                     | `oklch(0.505 0.213 27.518deg)`                        | `oklch(0.76 0.15 22.216deg)`      |
| `success`                       | Positive outcome                                                                                                                                                                                      | `oklch(0.448 0.119 151.328deg)`                       | `oklch(0.792 0.15 151.711deg)`    |
| `warning`                       | Needs attention, not blocking                                                                                                                                                                         | `oklch(0.473 0.137 46.201deg)`                        | `oklch(0.828 0.15 84.429deg)`     |
| `border`                        | Borders and dividers                                                                                                                                                                                  | `oklch(0.922 0 0deg)`                                 | `oklch(1 0 0deg / 20%)`           |
| `input`                         | Input and control borders                                                                                                                                                                             | `oklch(0.62 0 0deg)`                                  | `oklch(1 0 0deg / 40%)`           |
| `ring`                          | Focus ring                                                                                                                                                                                            | `oklch(0.62 0 0deg)`                                  | `oklch(0.66 0 0deg)`              |
| `destructive-foreground`        | Text on a destructive fill (derived)                                                                                                                                                                  | `background`                                          | `background`                      |
| `quiet-foreground`              | The quiet half of a summary row (derived)                                                                                                                                                             | `foreground` at 70%                                   | the same                          |
| `primary-fill`                  | The primary as a filled area under words — a highlighted item linked to it; a preset gives a deeper step where the primary is too light to carry white words (dark)                                   | `primary`                                             | `primary`                         |
| `primary-fill-foreground`       | The words on that fill                                                                                                                                                                                | `primary-foreground`                                  | `primary-foreground`              |
| `pin-shadow`                    | The soft edge of a pinned column; the mode's, never a preset's                                                                                                                                        | `oklch(0 0 0deg / 12%)`                               | `oklch(1 0 0deg / 10%)`           |
| `chart-1`                       | Chart colour slot 1: the first series                                                                                                                                                                 | `oklch(0.565 0.1626 255.532deg)`                      | `oklch(0.6221 0.1612 255.053deg)` |
| `chart-2`                       | Chart colour slot 2: the second series                                                                                                                                                                | `oklch(0.6708 0.175 40.642deg)`                       | `oklch(0.6221 0.1726 40.112deg)`  |
| `chart-3`                       | Chart colour slot 3: the third series                                                                                                                                                                 | `oklch(0.669 0.1408 162.111deg)`                      | `oklch(0.6212 0.1283 163.115deg)` |
| `chart-4`                       | Chart colour slot 4: the fourth series                                                                                                                                                                | `oklch(0.7644 0.1612 75.116deg)`                      | `oklch(0.6699 0.1425 73.227deg)`  |
| `chart-5`                       | Chart colour slot 5: the fifth series                                                                                                                                                                 | `oklch(0.7163 0.1412 357.389deg)`                     | `oklch(0.6224 0.1712 0.838deg)`   |
| `chart-6`                       | Chart colour slot 6: the sixth series                                                                                                                                                                 | `oklch(0.5285 0.1798 142.495deg)`                     | `oklch(0.5285 0.1798 142.495deg)` |
| `chart-7`                       | Chart colour slot 7: the seventh series                                                                                                                                                               | `oklch(0.4331 0.1671 283.624deg)`                     | `oklch(0.6696 0.1452 286.827deg)` |
| `chart-8`                       | Chart colour slot 8: the eighth series                                                                                                                                                                | `oklch(0.6226 0.1909 24.912deg)`                      | `oklch(0.6693 0.1586 22.307deg)`  |
| `radius`                        | Corner radius, the rest scale off it                                                                                                                                                                  | `0.625rem`                                            | —                                 |
| `text-ui`                       | The one size under the body text                                                                                                                                                                      | `0.8125rem`                                           | —                                 |
| `font-sans`                     | The type, a system font stack                                                                                                                                                                         | unset: the page's                                     | —                                 |
| `chart-patterns`                | Patterns over the chart series: `on`, `off`, or unset / `auto` to follow the reader's "increase contrast"                                                                                             | unset                                                 | —                                 |
| `brand`                         | The brand colour, on any preset: the primary, the tints of `accent`, `sidebar-accent` and a selected row, and the focus ring where the preset bounds it take its hue, each held to the preset's lines | unset                                                 | `brand`                           |
| `brand-l-min`                   | The primary's lower lightness bound: a brand darker than this is lifted to it                                                                                                                         | `0.4`                                                 | `0.68`                            |
| `brand-l-max`                   | The primary's upper lightness bound: a brand lighter than this is taken down to it                                                                                                                    | `0.5`                                                 | `0.8`                             |
| `brand-c-max`                   | The primary's and the ring's chroma ceiling                                                                                                                                                           | `0.37`                                                | `0.18`                            |
| `brand-ring-l-min`              | The focus ring's lower lightness bound; the ring is derived only when both ring bounds are given                                                                                                      | unset                                                 | the same                          |
| `brand-ring-l-max`              | The focus ring's upper lightness bound                                                                                                                                                                | unset                                                 | the same                          |
| `brand-primary-fill-l-min`      | The primary fill's lower lightness bound; the fill is derived only when both bounds are given                                                                                                         | unset                                                 | the same                          |
| `brand-primary-fill-l-max`      | The primary fill's upper lightness bound                                                                                                                                                              | unset                                                 | the same                          |
| `brand-accent-lc`               | The lightness and chroma (two numbers) the brand is set at for `accent`                                                                                                                               | `0.96 0.02`                                           | `0.3 0.03`                        |
| `brand-sidebar-accent-lc`       | The lightness and chroma the brand is set at for `sidebar-accent`                                                                                                                                     | `0.92 0.03`                                           | `0.3 0.03`                        |
| `brand-row-selected-lc`         | The lightness and chroma the brand is set at for a selected row                                                                                                                                       | `0.965 0.02`                                          | `0.28 0.03`                       |
| `brand-chart-1-lc`              | The lightness and chroma of the first chart slot under `data-fve-brand-chart` — the preset's own first slot's                                                                                         | `0.565 0.1626`                                        | `0.6221 0.1612`                   |
| `preset-density`                | The density a preset recommends: `-1`, `0` or `1` (a preset's; a host sets `data-fve-density`)                                                                                                        | unset                                                 | —                                 |
| `rise`                          | A rise, by its direction                                                                                                                                                                              | `success` (see [change colours](#rising-and-falling)) | `success`                         |
| `fall`                          | A fall, by its direction                                                                                                                                                                              | `destructive`                                         | `destructive`                     |
| `shadow-sm`                     | The low lift: a raised card                                                                                                                                                                           | Tailwind's `shadow-sm`                                | the same                          |
| `shadow-md`                     | The middle lift: a popup                                                                                                                                                                              | Tailwind's `shadow-md`                                | the same                          |
| `shadow-lg`                     | The high lift: a dragged panel                                                                                                                                                                        | Tailwind's `shadow-lg`                                | the same                          |
| `canvas`                        | The grouped ground a board and a host's card-laid page stand on (the token class `fve:bg-canvas`)                                                                                                     | `background`                                          | `background`                      |
| `content`                       | The ground rows and a result are written on                                                                                                                                                           | `background`                                          | `background`                      |
| `card-edge`                     | The ring round a card: a board panel, a record card                                                                                                                                                   | `foreground` at 10%                                   | the same                          |
| `card-shadow`                   | A card's lift off what it sits on                                                                                                                                                                     | `0 0 #0000`                                           | `0 0 #0000`                       |
| `scrim`                         | What dims the page behind a dialog or a sheet                                                                                                                                                         | `oklch(0 0 0deg / 10%)`                               | `oklch(0 0 0deg / 10%)`           |
| `table-header`                  | A table's header band                                                                                                                                                                                 | `muted`                                               | `muted`                           |
| `table-header-foreground`       | The words on the header band                                                                                                                                                                          | `foreground`                                          | `foreground`                      |
| `table-header-weight`           | The header's weight                                                                                                                                                                                   | `strong-weight`                                       | —                                 |
| `table-header-divider`          | A line between the header's columns (`transparent`: none)                                                                                                                                             | `transparent`                                         | `transparent`                     |
| `totals`                        | A table's totals band: the summary rows, an analysis's totals                                                                                                                                         | `muted`                                               | `muted`                           |
| `row-selected`                  | A selected row, a pressed group                                                                                                                                                                       | `muted`                                               | `muted`                           |
| `row-selected-foreground`       | The words on a selected row                                                                                                                                                                           | `foreground`                                          | `foreground`                      |
| `row-hover`                     | A hovered row (derived)                                                                                                                                                                               | `muted` halfway into `background`                     | the same                          |
| `row-selected-mark`             | A bar down a selected row's left edge: the selection said by more than its colour                                                                                                                     | unset: none                                           | the same                          |
| `row-selected-mark-link`        | How much of the resolved `primary` `row-selected-mark` is drawn in: `100%` is `primary` itself, following a brand colour and the mode                                                                 | unset: not linked                                     | —                                 |
| `row-stripe`                    | Every other row's ground (off: the rows' own)                                                                                                                                                         | `content`                                             | `content`                         |
| `row-divider`                   | The line between two body rows (`transparent`: none, where rows are striped)                                                                                                                          | `border`                                              | `border`                          |
| `table-sort-idle`               | How much a sortable column's idle mark shows where a pointer can hover (`0`: only under the pointer or focus)                                                                                         | `1`                                                   | —                                 |
| `highlight`                     | The item a menu, a select or a combobox has under the keyboard or the pointer                                                                                                                         | `accent`                                              | `accent`                          |
| `highlight-foreground`          | The words on that item                                                                                                                                                                                | `accent-foreground`                                   | `accent-foreground`               |
| `highlight-link`                | How much of the resolved `primary-fill` `highlight` is drawn in: `100%` is `primary-fill` itself, following a brand colour and the mode                                                               | unset: not linked                                     | —                                 |
| `highlight-foreground-link`     | How much of the resolved `primary-fill-foreground` `highlight-foreground` is drawn in: `100%` is `primary-fill-foreground` itself, following a brand colour and the mode                              | unset: not linked                                     | —                                 |
| `item-selected`                 | The item a menu, a select or a combobox holds chosen                                                                                                                                                  | `transparent`                                         | `transparent`                     |
| `item-selected-foreground`      | The words on that item                                                                                                                                                                                | `popover-foreground`                                  | `popover-foreground`              |
| `item-selected-weight`          | The weight of those words                                                                                                                                                                             | unset: the item's own                                 | —                                 |
| `item-selected-link`            | How much of the resolved `row-selected` `item-selected` is drawn in: `100%` is `row-selected` itself, following a brand colour and the mode                                                           | unset: not linked                                     | —                                 |
| `nav-current`                   | The view on screen in the view list                                                                                                                                                                   | `background`                                          | `background`                      |
| `nav-current-foreground`        | Its words                                                                                                                                                                                             | `foreground`                                          | `foreground`                      |
| `nav-current-edge`              | Its edge                                                                                                                                                                                              | `border`                                              | `border`                          |
| `nav-current-shadow`            | Its lift off the column                                                                                                                                                                               | `0 1px 2px 0 rgb(0 0 0 / 0.05)`                       | `0 1px 2px 0 rgb(0 0 0 / 0.05)`   |
| `nav-current-link`              | How much of the resolved `row-selected` `nav-current` is drawn in: `100%` is `row-selected` itself, following a brand colour and the mode                                                             | unset: not linked                                     | —                                 |
| `nav-current-foreground-link`   | How much of the resolved `primary` `nav-current-foreground` is drawn in: `100%` is `primary` itself, following a brand colour and the mode                                                            | unset: not linked                                     | —                                 |
| `control-hover`                 | A button or a toggle under the pointer                                                                                                                                                                | unset: each control's own                             | unset: each control's own         |
| `control-pressed`               | A toggle pressed                                                                                                                                                                                      | unset: `muted`                                        | unset: `muted`                    |
| `outline-hover-edge`            | The edge of an outline button under the pointer                                                                                                                                                       | unset: `border`                                       | unset: `input`                    |
| `outline-hover-foreground`      | Its words                                                                                                                                                                                             | `foreground`                                          | `foreground`                      |
| `outline-hover-edge-link`       | How much of the resolved `primary` `outline-hover-edge` is drawn in: `100%` is `primary` itself, following a brand colour and the mode                                                                | unset: not linked                                     | —                                 |
| `outline-hover-foreground-link` | How much of the resolved `primary` `outline-hover-foreground` is drawn in: `100%` is `primary` itself, following a brand colour and the mode                                                          | unset: not linked                                     | —                                 |
| `focus-width`                   | The width of a focused control's outline                                                                                                                                                              | unset: no outline, the registry's edge and halo       | —                                 |
| `focus-offset`                  | How far that outline stands off the control's edge                                                                                                                                                    | `0px`                                                 | —                                 |
| `focus-style`                   | That outline's style: `solid`, `dashed`, `double`…                                                                                                                                                    | `solid`                                               | —                                 |
| `focus-halo`                    | The halo round a focused control (`transparent`: none)                                                                                                                                                | `ring` at 50%                                         | the same                          |
| `control`                       | The rest fill of a control its words or icons name: a filter chip, a segmented control, a toolbar's outline buttons                                                                                   | unset: each control's own                             | unset: each control's own         |
| `control-edge`                  | That control's edge (a chip holding a text box keeps `input`)                                                                                                                                         | unset: each control's own                             | unset: each control's own         |
| `control-thumb`                 | A segmented control's pressed item, the thumb on its track                                                                                                                                            | unset: `muted`                                        | unset: `muted`                    |
| `control-thumb-shadow`          | The lift of that thumb off its track                                                                                                                                                                  | `0 0 #0000`                                           | `0 0 #0000`                       |
| `control-height`                | A control's height: a button, a text box, a select, a filter chip's controls                                                                                                                          | `2rem`                                                | —                                 |
| `control-height-sm`             | A small control's height: the toolbars' buttons                                                                                                                                                       | `1.75rem`                                             | —                                 |
| `filter-height`                 | A board filter chip's height, its controls filling it                                                                                                                                                 | unset: its controls and padding                       | —                                 |
| `edge-width`                    | The width of a control's edge (a divider stays 1px)                                                                                                                                                   | `1px`                                                 | —                                 |
| `badge-edge`                    | How much of its tone a toned badge's edge takes                                                                                                                                                       | `30%`                                                 | —                                 |
| `badge-fill`                    | How much of its tone a toned badge's wash takes                                                                                                                                                       | `10%`                                                 | —                                 |
| `radius-card`                   | A card's and a dialog's corner                                                                                                                                                                        | `radius` × 1.4                                        | —                                 |
| `radius-control`                | A control's corner (a small control's is 0.8 of it, at most 12px)                                                                                                                                     | `radius`                                              | —                                 |
| `radius-popover`                | A popup's corner                                                                                                                                                                                      | `radius`                                              | —                                 |
| `radius-badge`                  | A badge's corner                                                                                                                                                                                      | `radius` × 2.6                                        | —                                 |
| `radius-checkbox`               | A checkbox's corner                                                                                                                                                                                   | `4px`                                                 | —                                 |
| `title-weight`                  | The weight of a view's, a card's and a dialog's title                                                                                                                                                 | `500`                                                 | —                                 |
| `strong-weight`                 | The weight of what is strong beside its text: a table header, the totals                                                                                                                              | `500`                                                 | —                                 |
| `tooltip`                       | A tooltip's ground                                                                                                                                                                                    | `foreground`                                          | `foreground`                      |
| `tooltip-foreground`            | The words in a tooltip                                                                                                                                                                                | `background`                                          | `background`                      |
| `chart-grid`                    | A chart's gridlines and axis rules                                                                                                                                                                    | `border`                                              | `border`                          |
| `chart-grid-width`              | The width of a chart's gridlines                                                                                                                                                                      | `1px`                                                 | —                                 |
| `chart-axis`                    | A chart's quiet text: ticks, axis titles, a scale's ends, the names beside its marks                                                                                                                  | `muted-foreground`                                    | `muted-foreground`                |
| `chart-text-size`               | The size of a chart's text: ticks, axis titles, names                                                                                                                                                 | `text-ui` − 1px (12px)                                | —                                 |
| `chart-label-size`              | The size of a value label on a mark, a step under the chart's text                                                                                                                                    | `text-ui` − 2px (11px)                                | —                                 |
| `chart-line-width`              | The width of a line chart's line (a derived line is ¾ of it)                                                                                                                                          | `2px`                                                 | —                                 |
| `chart-area-opacity`            | How opaque the fill under an area chart's line is                                                                                                                                                     | `0.2`                                                 | —                                 |
| `chart-bar-radius`              | The corner of a bar's end (and of a funnel's stage, a heatmap's cell)                                                                                                                                 | `radius` × 0.6, at most 2px                           | —                                 |
| `chart-bar-min-width`           | The narrowest a bar is drawn                                                                                                                                                                          | unset: as narrow as the plot makes it                 | —                                 |
| `chart-bar-max-width`           | The widest a bar is drawn                                                                                                                                                                             | `80px`                                                | —                                 |
| `chart-slice-border`            | The seam between two slices of a pie, in the chart's ground                                                                                                                                           | `1px`                                                 | —                                 |
| `chart-map-edge`                | A map's boundaries, round every area with a number or without                                                                                                                                         | `chart-axis`                                          | `chart-axis`                      |
| `chart-tooltip`                 | A chart tooltip's ground                                                                                                                                                                              | `popover`                                             | `popover`                         |
| `chart-tooltip-foreground`      | The numbers in a chart tooltip                                                                                                                                                                        | `popover-foreground`                                  | `popover-foreground`              |
| `chart-tooltip-shadow`          | The lift of a chart tooltip                                                                                                                                                                           | `shadow-md`                                           | `shadow-md`                       |

<!-- theme-tokens:end -->

The type is the host's: the surface sets `font-family: var(--fve-font-sans, var(--fvp-font-sans))` — yours, then a preset's — which, unset, leaves `font-family` inherited from the page as before. Set `--fve-font-sans` to a system font stack to give the views one of their own; a chart reads the computed family and follows. It has no dark half.

The five `sidebar*` tokens are shadcn's own names for the navigation column the workbench puts its view list in, so a host that already themes a shadcn sidebar themes this one with the same words. Only the five the column paints with are declared. The open view in that column is `background` on top of `sidebar`, and `sidebar-accent` is the hover, so the three have to stay apart from one another: a set where two of them resolve to the same grey is a list with no "you are here".

`input` and `ring` owe a line the others do not: a control's edge and the focus mark are what WCAG 1.4.11 asks 3:1 of against what is behind them, and both defaults are tuned to clear it in either mode (measured in the browser by the Storybook contrast stories). They are values of their own on purpose. The usual shadcn brand theme re-points them — `--ring: var(--primary)`, `--input: var(--border)` — and that hands the 3:1 to a brand colour and a divider grey that owe nothing of the kind: an unticked checkbox becomes a hairline, a focused row a faint tint. Setting `--fve-primary` or `--fve-border` leaves them alone; a host that sets `--fve-ring` / `--fve-input` (or their `--fve-dark-` halves) owes its theme the same 3:1 and should measure it.

A few tokens are derived rather than set: `quiet-foreground`, the quiet half of a summary row, is `foreground` at 70%, and `destructive-foreground` is `background`, so a host that moves `--fve-foreground` or `--fve-background` moves them too. Each can still be set on its own (`--fve-quiet-foreground`, `--fve-destructive-foreground` and the `--fve-dark-` halves).

A chart is drawn in the colours, lengths and numbers read off these tokens, not in `var()`s, so it is told to read them again when one of these attributes changes on the surface or one of its ancestors: <!-- chart-attributes:begin -->`class`, `data-theme`, `data-fve-preset`, `data-fve-change-colors`, `data-fve-density`, `data-fve-brand-chart` or `style`<!-- chart-attributes:end -->. A value a stylesheet derives — `color-mix()`, `oklch(from …)`, a `calc()` — is resolved by the browser before the chart gets it. Switch a theme by one of those; a stylesheet swapped in with no attribute changing leaves the charts in the old colours.

`radius` and `text-ui` are the two tokens the dark block does not redeclare — a length is a length in either mode — so `--fve-radius` and `--fve-text-ui` set them for both and there is no `--fve-dark-` half. `text-ui` is the one step under the body size: the group labels, column headers, badges, pagination and every `sm` control are set in it, so a host that scales it moves them together.

The root paints `--background`, so an embedded view shows its own rectangle inside a host card — in dark mode `--card` is a step lighter than `--background`, and the embed reads as a darker block. Give it the colour of what it sits on: set `--fve-background` and `--fve-dark-background` to your card's colour on the card (they inherit, so the embed under it picks them up and nothing else does). Not `transparent`: the rows, a pinned column, the hover shade and the ink on a destructive button are drawn in `--background`, so a transparent one lets a scrolled column show through the pinned one and leaves that button's label invisible.

Popups — menus, lists, popovers, tooltips and dialogs — are portalled to `<body>` and paint at `z-index: 50`, above the page around them. A host whose own chrome stacks higher than that raises every one of them with a single variable:

```css
:root {
  --fve-popup-z-index: 2000;
}
```

A view that fills the screen (「铺满屏幕」 on a workbench or an embed) is pinned to the viewport at level `0`: it covers the page's ordinary content, and chrome the host raised on purpose still covers it. A shell whose sidebar is `position: fixed` above that — shadcn's sidebar sits at `z-index: 10` — would hide the view's first columns, so such a host lifts the expanded view above its chrome and below the popups:

```css
:root {
  --fve-expanded-z-index: 20;
}
```

It is one of ten host variables that are lengths and levels of the layout rather than the theme — no preset sets them, and they have no dark half. The first five are the [density](#density)'s lengths: the density step gives their default, and a value of yours wins over it:

<!-- layout-variables:begin -->

| Variable                          | Role                                                                                                                   | Default                        |
| --------------------------------- | ---------------------------------------------------------------------------------------------------------------------- | ------------------------------ |
| `--fve-table-header-height`       | A table's header row, over the density                                                                                 | by the density: 32 / 40 / 44px |
| `--fve-table-cell-padding-block`  | The space above and below a table cell's value, over the density                                                       | by the density: 4 / 8 / 10px   |
| `--fve-table-cell-padding-inline` | The space either side of a table cell's value, over the density                                                        | by the density: 6 / 8 / 12px   |
| `--fve-sidebar-item-height`       | A view in the view list, over the density; keep it 24px or more (WCAG 2.5.8)                                           | by the density: 24 / 28 / 32px |
| `--fve-panel-padding`             | The space round a dashboard panel's content, over the density; above and below it stops at 12px (the board's 80px row) | by the density: 8 / 12 / 16px  |
| `--fve-expanded-z-index`          | The stacking level of a view that fills the screen, against the host page                                              | `0`                            |
| `--fve-popup-z-index`             | The stacking level every popup is portalled at                                                                         | `50`                           |
| `--fve-record-table-max-h`        | The height a record or analysis table stops at and scrolls inside (`size="content"`)                                   | `70vh`                         |
| `--fve-record-text-max-w`         | How wide a `text` cell grows before it wraps                                                                           | `24rem`                        |
| `--fve-workbench-min-height`      | The floor under a workbench in a container of no definite height                                                       | `36rem`                        |

<!-- layout-variables:end -->

## Your own chrome: `fve-tokens`

Every rule of the stylesheet is scoped at build time, so the theme's tokens, and the utilities the engine draws with, paint inside a style boundary and nowhere else. There are two boundaries, and only one of them is a surface:

|                                       | `.fve-root`                                                  | `.fve-tokens`                                                            |
| ------------------------------------- | ------------------------------------------------------------ | ------------------------------------------------------------------------ |
| Rendered by                           | `ViewSurface`, and every workbench and embed                 | your own markup                                                          |
| Tokens, utilities, preflight          | yes                                                          | yes                                                                      |
| Paints a background and a text colour | yes                                                          | **no** — paint `var(--background)` and `var(--foreground)` yourself     |
| Light or dark                         | a `.dark` ancestor, or `theme` pinning one with `data-theme` | a `.dark` ancestor, and nothing else                                     |
| Wording, locale, time zone, tooltips  | yes, through `ViewSurface`'s props                           | no                                                                       |

**What `fve-tokens` promises is the tokens, not the utilities and not components.** Inside the boundary the theme's tokens are declared under their shadcn names — `--background`, `--foreground`, `--card`, `--card-foreground`, `--primary`, `--muted`, `--border`, `--radius` and the rest — resolved for the preset, your overrides and the mode. Your own Tailwind theme maps them the way shadcn/ui's does (`--color-card: var(--card)`), so your own classes (`bg-card`) and your own copy of shadcn/ui wear this theme there; plain CSS reads them with `var(--card)`. The engine's `fve:` utilities are its own: the stylesheet holds one only while a component of the engine writes it, so one that works today can be gone in the next release. The shadcn primitives this package renders with are vendored, updated with `shadcn add --diff`, and not public either — so build your chrome from your own components and classes, and let the boundary give them this theme's colours:

<!-- typecheck-context
import { ViewEngine } from '@ahoo-wang/wow-view-engine';
declare const engine: ViewEngine;
import { EmbeddedView } from '@ahoo-wang/wow-view-engine/ui';
declare const id: string;
-->

```tsx
<div className="fve-tokens flex flex-col gap-4">
  <header className="flex items-center gap-2 rounded-lg border bg-card p-4 text-card-foreground">
    …your own header, your own classes, this theme's tokens…
  </header>
  <EmbeddedView engine={engine} instanceId={id} theme="light" />
</div>
```

One class is offered as a token: `fve:bg-canvas`, the grouped ground of a board (`canvas`), which has no shadcn name to map. A page of cards that a board sits on paints itself with it, so the page and the board are one ground. It is the only class of the stylesheet that is public.

### What is public

What a host may rely on, release to release, is this — a change to any of it is a breaking change:

- the variables: `--fve-*` and `--fve-dark-*` that you write, `--fvp-*` and `--fvp-dark-*` that a preset writes ([Every variable](#every-variable)), and the shadcn-named tokens the two boundaries declare for you to read;
- the attributes the theme reads: `data-fve-preset`, `data-fve-density`, `data-fve-change-colors`, `data-fve-brand-chart`, and `data-theme` on a surface;
- the two boundaries, `.fve-root` and `.fve-tokens`, and the `.dark` class the mode follows;
- the token class `fve:bg-canvas`.

Everything else is the engine's and changes without notice: every other `fve:` class, the `data-slot` attributes on its elements, its other class names, the `--_fve-*` variables, and the DOM it renders — its elements, their order and their nesting. Do not select on them; a rule that does may stop matching in any release.

`fve-tokens` reads exactly one thing for the mode: a `.dark` class on an ancestor, the same one the surfaces follow — set it on `<html>`, on your app shell, wherever your application already keeps it. It reads no `data-theme` of its own: pinning a mode is what a surface is for. And it hands every element a surface answers for back to that surface, so the view above stays light inside a dark page, every token of it.

Preflight applies inside the boundary too: your own headings, lists and buttons in that region are reset the same way they would be inside a view. That is the price of the boundary, and it is why the class goes on the chrome that reads the tokens rather than on the whole page.

A popup of your own leaves the shell for `<body>`, so its portal wears the class too — `<Menu.Portal className="fve-tokens">` — as the engine's own popups carry their surface's theme.

## See it

The [theme gallery](/storybook/?path=/docs/view-engine-能力-主题与预设--docs) has one story per preset, a light and a dark band each: a record view, an analysis chart and a dashboard with its filter bar. Beside it are a page per preset with a whole report in a host's shell, the brand colour on three presets, the contrast matrix, and a host's own theme. The Storybook toolbar has a **Preset** switch and a **system** mode for every other story.
