---
title: Theming the View Engine
description: How the unreleased wow-view-engine takes a host's look — three layers of variables, presets, a brand colour as an input, the engine's roles and links, the charts' roles, light, dark and system mode, pinning, embeds and popups, the shadcn bridge, and the contrast an override owes.
---

# Theming the View Engine

::: warning Not released
`@ahoo-wang/wow-view-engine` has not been published to npm and carries no compatibility promise. This page describes theming as it stands in the repository.
:::

The theme is the host's look, not a way of observing: nothing about it is saved in a view, a dashboard or a preference, and the workbench has no theme switch. The host picks a preset, a brand colour and a mode; the engine follows. Everything below is CSS custom properties — there is no theme object and no provider.

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

A `--_fve-*` name is the engine's own and changes without notice; a `--fve-*` or `--fvp-*` name the registry does not list does nothing. The full list is the token table of the [package README](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/README.md#customising-the-theme), generated from the theme's registry, and `dist/theme-tokens.json` is that registry as data.

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
  --fve-primary: oklch(0.55 0.21 265deg);
  --fve-primary-foreground: oklch(0.99 0 0deg);
  --fve-dark-primary: oklch(0.75 0.15 265deg);
  --fve-dark-primary-foreground: oklch(0.21 0.05 265deg);
  --fve-radius: 0.375rem;
}
```

They win over the preset you chose and over one pinned on a surface, as [Three layers](#three-layers) says. For one surface alone, pass `tokens` to `ViewSurface`, a workbench or an embed rather than setting the variables on a wrapper: a popup is portalled to `<body>`, out from under the wrapper, and `tokens` is written on the surface and on every popup it opens. Its type, `FveToken` from `/ui`, is every host variable of the registry.

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
| Tables              | `table-header`, `table-header-foreground`, `table-header-weight`, `table-header-divider`, `totals`, `row-selected`, `row-selected-foreground`, `row-hover`, `row-stripe` (off unless set)                                                                                                                                                                                                                                                            |
| States              | `highlight`, `highlight-foreground` (the item a menu, a select or a combobox has under the keyboard); `item-selected`, `item-selected-foreground`, `item-selected-weight` (the item it holds chosen); `nav-current`, `nav-current-foreground`, `nav-current-edge`, `nav-current-shadow` (the view on screen in the list); `control-hover`, `control-pressed`; `outline-hover-edge`, `outline-hover-foreground` (an outline button under the pointer) |
| Focus               | `focus-width`, `focus-offset`, `focus-style`, `focus-halo`                                                                                                                                                                                                                                                                                                                                                                                           |
| Controls            | `control`, `control-edge`, `control-thumb`, `control-thumb-shadow`, `control-height`, `control-height-sm`, `filter-height` (a board's filter chip), `edge-width`, `badge-edge`, `badge-fill`                                                                                                                                                                                                                                                         |
| Corners             | `radius-card`, `radius-control`, `radius-popover`, `radius-badge`, `radius-checkbox`                                                                                                                                                                                                                                                                                                                                                                 |
| Type and tooltips   | `title-weight`, `strong-weight`, `tooltip`, `tooltip-foreground`                                                                                                                                                                                                                                                                                                                                                                                     |
| Charts              | see [The charts' roles](#the-charts-roles)                                                                                                                                                                                                                                                                                                                                                                                                           |

- **The lines a role owes**: a control stays 24px tall or more at either height (WCAG 2.5.8), and a theme promising AAA text gives its focus outline 2px or more (WCAG 2.4.13). Every role that is a ground is in the contrast matrix like the rest, so a theme that parts it from its token is measured on what it paints.
- **The words and defaults** for each role are in the token table of the [package README](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/README.md#roles).

### Role links

A colour a preset writes is fixed where the preset is named — usually `<html>` — so a preset cannot say "the menu highlight is the primary": that primary is resolved later, on the view, from your brand colour or your own `--fve-primary`. A **link** says it instead. `<role>-link` is a share of another token the view has resolved, `100%` being that token itself and less a translucent wash of it over the ground:

| Link                            | Draws                      | In                   |
| ------------------------------- | -------------------------- | -------------------- |
| `highlight-link`                | `highlight`                | `primary`            |
| `highlight-foreground-link`     | `highlight-foreground`     | `primary-foreground` |
| `item-selected-link`            | `item-selected`            | `row-selected`       |
| `nav-current-link`              | `nav-current`              | `row-selected`       |
| `nav-current-foreground-link`   | `nav-current-foreground`   | `primary`            |
| `outline-hover-edge-link`       | `outline-hover-edge`       | `primary`            |
| `outline-hover-foreground-link` | `outline-hover-foreground` | `primary`            |

```css
:root {
  --fve-highlight-link: 100%;
  --fve-highlight-foreground-link: 100%;
}
```

With those two, every menu, select and combobox highlights its item with the primary's fill and ink — your brand's, in either mode. A link is one number for both modes, since its target resolves in each. A role colour you set wins over a link, and a link, yours or a preset's, wins over the preset's own colour; unset, the role is what it was. `porcelain` links its menu highlight, and `azure` its current view, its chosen item and an outline button's hovered edge — which is why they follow a brand colour.

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

| How                                                                         | Effect                                                                                  |
| --------------------------------------------------------------------------- | --------------------------------------------------------------------------------------- |
| A `.dark` class on any ancestor, usually `<html>`                           | Views follow your page's mode                                                           |
| `theme="light"` or `theme="dark"` on `ViewSurface`, a workbench or an embed | Pins that one view                                                                      |
| `theme="system"`                                                            | Follows the reader's `prefers-color-scheme`, live, for a page with no switch of its own |

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

For a host's CI, the package ships a command that holds a stylesheet to the same gates, by the registry and the arithmetic the package's own tests use:

```bash
pnpm exec wow-view-engine theme-check src/theme.css
pnpm exec wow-view-engine theme-check src/theme.css --preset azure --json
```

It reports, with the line and column: a `--fve-*` or `--fvp-*` the registry does not list, a `--_fve-*` written or read, a variable in the wrong layer, a preset inside an `@layer`, a chart palette or shadow ladder given in part; Tailwind v3 HSL channels where a colour is wanted, with the `hsl()` to write; brand bounds out of range, crossed, or letting some brand colour fall short (it sweeps them across sRGB); every contrast pair in both modes and every change convention, on each preset of yours and on the built-in presets your `:root` variables are worn on (`--preset` narrows them); and the chart palette's three gates when you bring one (or pass `--brand-chart`). It exits 1 on an error and 0 on warnings alone. It cannot see what your page does at run time, so the contrast matrix stays the check of what a browser paints. The details are in the [package README](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/README.md#checking-a-theme-theme-check).

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

The five are `--fve-table-header-height`, `--fve-table-cell-padding-block` and `--fve-table-cell-padding-inline` (a table's header row, and the space above and below and either side of a value), `--fve-sidebar-item-height` (a view in the list) and `--fve-panel-padding` (round a dashboard panel's content; above and below it stops at 12px, so a tile one 80px row tall keeps room for its number). They are layout, not theme: no preset sets them, a preset only recommends a step. Nothing floors them either — a view in the list is a button, so keep `--fve-sidebar-item-height` at 24px or more (WCAG 2.5.8).

## See it

The [theme gallery](/storybook/?path=/docs/view-engine-能力-主题与预设--docs) has one story per preset, a light and a dark band each: a record view, an analysis chart and a dashboard with its filter bar. Beside it are a page per preset with a whole report in a host's shell, the brand colour on three presets, the contrast matrix, and a host's own theme. The Storybook toolbar has a **Preset** switch and a **system** mode for every other story.
