---
title: Theming the View Engine
description: How the unreleased wow-view-engine takes a host's look — presets, host variables, light, dark and system mode, pinning, embeds and popups, the shadcn bridge, and the contrast lines an override owes.
---

# Theming the View Engine

::: warning Not released
`@ahoo-wang/wow-view-engine` has not been published to npm and carries no compatibility promise. This page describes theming as it stands in the repository.
:::

The theme is the host's look, not a way of observing: nothing about it is saved in a view, a dashboard or a preference, and the workbench has no theme switch. The host picks a preset and a mode; the engine follows. Everything below is CSS custom properties — there is no theme object and no provider.

## Stylesheets

| Entry | What it is | Import it when |
|---|---|---|
| `@ahoo-wang/wow-view-engine/styles.css` | The theme: every rule scoped inside the view's own boundary, every colour a token that reads a host variable | Always |
| `@ahoo-wang/wow-view-engine/themes.css` | The built-in presets, keyed by a `data-fve-preset` attribute | You switch presets at run time |
| `@ahoo-wang/wow-view-engine/themes/<name>.css` | One built-in preset alone, the same block `themes.css` holds for it | You wear one preset |
| `@ahoo-wang/wow-view-engine/shadcn-bridge.css` | Your shadcn/ui tokens read into the view's host variables | Your app already has a shadcn theme |

The optional files only assign `--fve-*` variables: they paint nothing and never touch a variable of yours. The package's build checks that on every release.

## Presets

Picking a look is one line: import the preset's file and name it on `<html>`.

```ts
import '@ahoo-wang/wow-view-engine/styles.css';
import '@ahoo-wang/wow-view-engine/themes/porcelain.css';
```

```html
<html data-fve-preset="porcelain">
```

Import `themes.css` instead to have every preset and switch at run time. The attribute on `<html>` reaches every view and every popup; to give one view its own, pass `preset` (see [Pinning a preset](#pinning-a-preset)). `/ui` exports `BUILT_IN_PRESETS`, the list of built-in names, for a picker in your own chrome — the engine draws none.

| Preset | Character | Corners | Chart colours |
|---|---|---|---|
| `neutral` | The default: neutral greys, a black primary | 10px | default |
| `azure` | Chinese enterprise admin: a clear blue, white cards on a grey page, a type stack with the Chinese faces first | 6px | its own |
| `porcelain` | Native desktop: system type, large corners, soft shadows, near-neutral greys | 12px | its own |
| `contrast` | High contrast: text at 7:1, edges and focus at 4.5:1, chart patterns on | 4px | its own |
| `brand` | `neutral`, with the primary and a tint derived from one colour you give | 10px | default |

Which one fits your brand:

| Your situation | Use |
|---|---|
| Your app is a shadcn app with its own theme | [`shadcn-bridge.css`](#the-shadcn-bridge), no preset |
| No design system, and you want a ready look | The preset above closest to your product |
| A back office in the style of the open-source kits common in China | `azure`, with `data-fve-change-colors="red-up"` on boards read by mainland-China markets |
| A macOS / Apple desktop-app feel | `porcelain` |
| Only a brand colour | `brand` with `--fve-brand` (see [One brand colour](#one-brand-colour)) |
| A full design specification | The closest preset, then override the few `--fve-*` that differ on `:root` |

Each preset gives both a light and a dark half, measured pair by pair: text at 4.5:1, a control's edge and the focus mark at 3:1, and a palette of its own through the same colour-vision gates as the default eight. The values each one sets, and why, are in the package's `src/themes/<name>.css`.

- **A preset and the mode are independent.** The preset supplies both halves of the values; light or dark is still decided as described under [Light, dark and system](#light-dark-and-system).
- **A preset gives every colour and `radius`**, and may add four optional groups, each whole or not at all: its own eight chart colours, its three shadows, a system font stack (`--fve-font-sans`), and the chart patterns' pin (`--fve-chart-patterns`, which only `contrast` sets). It never sets `pin-shadow`, `text-ui` or the rise and fall colours. See [Chart colours](#chart-colours) and [Rising and falling](#rising-and-falling).
- **Your own preset** is written the same way and selected by the same attribute: `:where([data-fve-preset='acme']) { --fve-primary: …; --fve-dark-primary: …; }`. The built-in presets use this same contract and nothing else — only the documented `--fve-*` variables, no private selector, no code path for one preset — so what they do, yours can do. To check yours, paste its declarations into the [contrast matrix](/storybook/?path=/story/view-engine-能力-主题与预设--contrast): they are measured beside the built-in presets, pair by pair, and its chart colours through the palette gates. The Storybook page [A host's own theme](/storybook/?path=/story/view-engine-能力-主题与预设-宿主自定义主题--host-authored) is a complete example, a stylesheet outside the package held to the same gates.

## One brand colour

If all you have is a brand colour, that is enough for a whole theme: pick `brand` and give it the colour.

```ts
import '@ahoo-wang/wow-view-engine/styles.css';
import '@ahoo-wang/wow-view-engine/themes/brand.css';
```

```html
<html data-fve-preset="brand" style="--fve-brand: #7c3aed">
```

The primary and a faint tint of the selection and the hovered row come from your colour in OKLCH, with the lightness clamped so that any colour holds every contrast line — 0.40–0.50 in light, 0.68–0.80 in dark. Everything else is `neutral`. `--fve-dark-brand` gives the dark half its own colour. Put the variable on the element that carries `data-fve-preset` or above it. Without a colour, or in a browser older than Chrome 119, Safari 18 or Firefox 128, the page is `neutral`.

## Host overrides

Every token reads a host variable with the built-in value as its fallback: `--fve-<token>` for light and `--fve-dark-<token>` for dark. Set them on your `:root`:

```css
:root {
  --fve-primary: oklch(0.55 0.21 265deg);
  --fve-primary-foreground: oklch(0.99 0 0deg);
  --fve-dark-primary: oklch(0.75 0.15 265deg);
  --fve-dark-primary-foreground: oklch(0.21 0.05 265deg);
  --fve-radius: 0.375rem;
}
```

Presets and the bridge are written as `:where(…)`, which weighs nothing, so a variable you set on `:root` wins over the preset you chose, whichever stylesheet loads first. Override one colour of a preset without restating the rest. The full token list is in the [package README](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/README.md#customising-the-theme).

## Light, dark and system

| How | Effect |
|---|---|
| A `.dark` class on any ancestor, usually `<html>` | Views follow your page's mode |
| `theme="light"` or `theme="dark"` on `ViewSurface`, a workbench or an embed | Pins that one view |
| `theme="system"` | Follows the reader's `prefers-color-scheme`, live, for a page with no switch of its own |

## Pinning a preset

`preset="porcelain"` on `ViewSurface`, a workbench or an embed pins that view to a preset, whatever `<html>` says. A `data-fve-preset` on any other ancestor works too: the surface finds the nearest one.

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

Menus, selects, popovers, tooltips and dialogs are portalled to `<body>`, outside the part of the page the view sits in. They carry what the surface resolved — its mode as `data-theme` and its preset as `data-fve-preset` — so a pinned embed's popups match it.

- **Use an attribute, not a stylesheet swap.** A chart reads its colours off the tokens and is told to read them again when a `class`, `data-theme`, `data-fve-preset`, `data-fve-change-colors` or `style` attribute changes on the surface or an ancestor. A stylesheet replaced with no attribute changing leaves charts in the old colours.
- **Variables set on one element stop at `<body>`.** `--fve-*` set on a card around an embed reach the embed but not its popups, which are not inside the card. Put page-wide values on `:root`; use `preset` for one view.
- **An embed on a card** paints `--background`. Give it the card's colour on the card — `--fve-background` and `--fve-dark-background` — rather than `transparent`.
- **Stacking**: popups paint at `z-index: 50`; raise them all with `--fve-popup-z-index` on `:root`.

## The shadcn bridge

```ts
import '@ahoo-wang/wow-view-engine/styles.css';
import '@ahoo-wang/wow-view-engine/shadcn-bridge.css';
```

The bridge points each `--fve-<token>` and `--fve-dark-<token>` at the shadcn token of the same name — `background`, `foreground`, `card`, `popover`, `primary`, `secondary`, `muted`, `accent` with their `-foreground` pairs, `border`, the five `sidebar*` tokens and `radius`. It is resolved on `<html>`, so it takes your values in the mode `<html>` is in.

| Not bridged | Why |
|---|---|
| `input`, `ring` | A shadcn theme often writes `--input: var(--border)` and `--ring: var(--primary)`: a divider grey and a brand colour that owe nothing of the 3:1 a control's edge and a focus mark need |
| `destructive`, `success`, `warning` | Text colours measured to 4.5:1 in both modes; shadcn has no `success` or `warning` |
| The eight chart colours | shadcn palettes have five, often starting on red; these are measured for colour-vision distance |
| The shadows | shadcn has no standard name for them (the type is bridged: `--fve-font-sans` from your `--font-sans`) |
| `row-hover`, `quiet-foreground` | Derived from bridged tokens |

To adopt it in an app with an existing shadcn theme:

1. Keep your `:root` and `.dark` blocks as they are, with `.dark` on `<html>`.
2. Import `styles.css` and `shadcn-bridge.css`. Drop any `data-fve-preset` on `<html>`: the bridge applies only while `<html>` names no preset, so a preset there wins.
3. Let views follow your page's mode. A view pinned to the opposite mode would read your current values in both halves.
4. Override what you want beyond the bridge one variable at a time, for example `--fve-ring`, and measure it (below).
5. Check your own text tokens: they are carried across as they are. A `--muted-foreground` that misses 4.5:1 on your `--background` misses it in the views too.

A Storybook regression test (`ShadcnBridge.test.stories.tsx`) hangs the compensation console's shadcn theme on a workbench with the bridge and measures its control edges and focus in both modes.

## Contrast is the overrider's responsibility

Every built-in preset holds these lines in both modes, measured in a real browser on every token pair:

| Line | Tokens |
|---|---|
| Text, ≥4.5:1 | Every `*-foreground` on its ground; `muted-foreground` on `background`, `card` and `popover`; `foreground` on `muted` and `row-hover`; `quiet-foreground`; `destructive`, `success` and `warning` as text on `background` and `card` |
| Controls and focus, ≥3:1 | `input` and `ring` on `background`, `card` and `popover`, and on a dark control's own `input/30` wash |
| None | `border` and `sidebar-border` (dividers), `radius`, `text-ui` |

When you set `--fve-ring` or `--fve-input` — or their `--fve-dark-` halves — you take over the 3:1: an unticked checkbox is only its `input` edge, and a focused control is known by its `ring` edge. Setting `--fve-primary` or `--fve-border` leaves both alone.

Measure a theme of your own in the Storybook [contrast matrix](/storybook/?path=/story/view-engine-能力-主题与预设--contrast): paste your `--fve-*` declarations into its field and they are measured beside the built-in presets, pair by pair.

## Chart colours

The eight chart slots are tuned for colour-vision distance between neighbouring slots (the eighth beside the first) and for a legible label ink on every mark, in both modes, and are not bridged. A preset may bring its own eight — all sixteen, both modes, or none — held to the same gates. A slot is an ordinal, "the third series", not a hue: a chart that says `var(--chart-3)` changes colour with the preset, and what means good or bad, up or down, is written with a status colour or `--rise` / `--fall` instead. A host can still set `--fve-chart-1` … `--fve-chart-8` and their `--fve-dark-` halves; it then owes its palette those measurements. Colours saved in a chart's own config are declared in code.

## Rising and falling

A metric card's change and a waterfall's steps are coloured by the host's change convention, `data-fve-change-colors` on `<html>`:

| Value | A metric card's change | A waterfall's rise / fall |
| --- | --- | --- |
| unset, or `semantic` | good or bad (`success` / `destructive`) | `success` / `destructive` |
| `green-up` | up or down | `success` / `destructive` |
| `red-up` | up or down, red for up | `destructive` / `success` |

It is the host's call by market and reader — never switched by the interface language, never set by a preset, and not a prop, since one page reads one market. `--fve-rise` / `--fve-fall` set the colours themselves. The direction is never said by colour alone: the card's change carries an arrow and a sign, and a waterfall's labels are signed.

## Density

`data-fve-density` on `<html>` — `compact`, `default` or `comfortable` — sets how tightly tables, the view list and dashboard panels sit: a header row of 32, 40 or 44px, 6, 8 or 12px beside a value, views of 24, 28 or 32px, 8, 12 or 16px round a panel. Controls, type and the dashboard's 80px row never change. `density` on a surface pins one view. Without either, a surface sits where its preset recommends (`porcelain` comfortable), and at `default` it draws exactly what it drew before.

## See it

The [theme gallery](/storybook/?path=/docs/view-engine-能力-主题与预设--docs) shows every preset in light, dark and system mode: a dashboard with its filter bar, a record table panel and an analysis chart panel, the same records as cards, and an export dialog. The Storybook toolbar has a **Preset** switch and a **system** mode for every other story.
