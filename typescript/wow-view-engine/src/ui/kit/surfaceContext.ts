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

import * as React from 'react';
import type { FveToken } from '../theme/tokens.js';

/*
 * What a surface hands to what renders outside it (`ViewSurface` provides
 * it, `popups.tsx` carries it onto every popup portalled to the body): the
 * mode, the preset, the change convention, the brand's chart slot, the
 * density, the type, the language and the host's own variables. Apart from
 * `ViewSurface.tsx`, so a popup reads them without importing the surface
 * that draws its own popups.
 */

/**
 * The mode a surface is actually in, for what renders outside it. A popup is
 * portalled to the document body, where `.fve-root` and its `data-theme` are
 * not ancestors; `popups.tsx` reads this to carry both onto the popup. It
 * holds the pinned `theme` when there is one, and otherwise the mode the
 * surface resolved from the cascade — so a `.dark` on any ancestor, not only
 * on `<html>`, reaches the popup too.
 */
export const SurfaceThemeContext = React.createContext<
  'light' | 'dark' | undefined
>(undefined);

export function useSurfaceTheme(): 'light' | 'dark' | undefined {
  return React.useContext(SurfaceThemeContext);
}

/**
 * The preset a surface is in, for what renders outside it — the pinned
 * `preset`, or else the one on its nearest ancestor. A preset is a set of
 * `--fve-*` values keyed by `data-fve-preset`; the values inherit down the
 * tree, and a popup portalled to the body is no longer under the element
 * that carries them, so `popups.tsx` writes the attribute onto the popup
 * itself, exactly as it writes `data-theme`. It is the attribute's value and
 * nothing else — the colours stay in the stylesheet.
 */
export const SurfacePresetContext = React.createContext<string | undefined>(
  undefined,
);

/**
 * The change convention a surface is under, for what renders outside it —
 * the `data-fve-change-colors` of its nearest ancestor with one, which a
 * popup portalled to the body is no longer under. `popups.tsx` writes it onto
 * the popup, as it writes the preset. There is no prop: the convention is the
 * whole page's market, and two on one page would be read the wrong way round
 * (themes.md 4.1).
 */
export const SurfaceChangeColorsContext = React.createContext<
  string | undefined
>(undefined);

/**
 * Whether the first chart slot follows the host's brand colour, for what
 * renders outside the surface — the `data-fve-brand-chart` of its nearest
 * ancestor with one (present is on, theme-architecture.md 2), which
 * `popups.tsx` writes onto a popup as it writes the change convention, so a
 * chart in a dialog keeps the page's first colour.
 */
export const SurfaceBrandChartContext = React.createContext<string | undefined>(
  undefined,
);

/**
 * The density a surface sits at, for what renders outside it — the pinned
 * `density`, or else the `data-fve-density` of its nearest ancestor with one.
 * `popups.tsx` writes it onto a popup as it writes the preset, so a table in
 * a dialog sits as densely as the view it opened from (themes.md 2.4).
 */
export const SurfaceDensityContext = React.createContext<string | undefined>(
  undefined,
);

/**
 * The type a surface is set in, for what renders outside it: the computed
 * `font-family` of its root, whatever put it there — a preset's stack, the
 * host's `--fve-font-sans`, or, when neither names one, the family the root
 * inherited from wherever the host set its type (an application frame, not
 * necessarily `<body>`). A popup portalled to the body inherits the body's
 * instead, which on a page that sets its type on a frame is the browser's
 * serif default; `popups.tsx` hands this to the popup as `--_fve-surface-font`,
 * which the stylesheet sets it in (themes.md 2.4). A value read back off the
 * cascade, like the tokens, not a second theme.
 */
export const SurfaceFontContext = React.createContext<string | undefined>(
  undefined,
);

export function useSurfaceFont(): string | undefined {
  return React.useContext(SurfaceFontContext);
}

/**
 * The language a surface's words are in, for what renders outside it: a
 * popup portalled to the body sits under the host's `<html lang>`, and its
 * words are the surface's (WCAG 3.1.2). `popups.tsx` writes it as the
 * popup's own `lang`, as it writes the mode.
 */
export const SurfaceLanguageContext = React.createContext<string | undefined>(
  undefined,
);

/**
 * The host variables a surface was handed (`tokens`), for its popups to
 * carry: a value the host gave this surface alone is not on any ancestor of
 * the portal, so `popups.tsx` writes it onto each popup itself, as it writes
 * the type. The values are the host's, passed along — not read back.
 */
export const SurfaceHostTokensContext = React.createContext<
  Partial<Record<FveToken, string>> | undefined
>(undefined);

export function useSurfaceHostTokens():
  Partial<Record<FveToken, string>> | undefined {
  return React.useContext(SurfaceHostTokensContext);
}

/**
 * The attributes a popup takes from the surface it opened from, so the
 * stylesheet resolves it as it does the surface: the mode, the preset, the
 * change convention, the density and whether the first chart slot follows
 * the brand. Spread onto every element a popup portals out.
 */
export function useSurfaceAttributes(): {
  lang: string | undefined;
  'data-theme': 'light' | 'dark' | undefined;
  'data-fve-preset': string | undefined;
  'data-fve-change-colors': string | undefined;
  'data-fve-density': string | undefined;
  'data-fve-brand-chart': string | undefined;
} {
  return {
    lang: React.useContext(SurfaceLanguageContext),
    'data-theme': React.useContext(SurfaceThemeContext),
    'data-fve-preset': React.useContext(SurfacePresetContext),
    'data-fve-change-colors': React.useContext(SurfaceChangeColorsContext),
    'data-fve-density': React.useContext(SurfaceDensityContext),
    'data-fve-brand-chart': React.useContext(SurfaceBrandChartContext),
  };
}
