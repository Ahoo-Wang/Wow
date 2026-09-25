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
 * The presets `@ahoo-wang/wow-view-engine/themes.css` ships, by name, in the
 * order it lists them (themes.md 4.1, D35) — for a host that builds its own
 * picker, and for the completion of a `preset` prop. The values are in the
 * stylesheet, never here: this is a list of names, which
 * `test/themeFiles.test.ts` holds to the stylesheet's.
 *
 * - `neutral` — the stylesheet's own look, and the default.
 * - `slate` — cool greys with a blue brand colour.
 * - `azure` — a Chinese enterprise admin look: a clear blue, 6px corners,
 *   white cards on a grey page, a type stack with the Chinese faces first.
 * - `porcelain` — a native desktop look: the system type, larger corners,
 *   soft shadows, near-neutral greys.
 * - `graphite` — square corners, a strong grey scale, no shadows: an
 *   operations console.
 * - `fjord` — cool, low-chroma Nordic colours for tools read all day.
 * - `contrast` — high contrast: text at 7:1, edges at 4.5:1, chart
 *   patterns on.
 * - `brand` — `neutral` with the primary derived from one host colour,
 *   `--fve-brand`.
 *
 * There are no display names: the engine draws no picker, and a host that
 * does names them in its own words.
 */
export const BUILT_IN_PRESETS = [
  'neutral',
  'slate',
  'azure',
  'porcelain',
  'graphite',
  'fjord',
  'contrast',
  'brand',
] as const;

/** The name of a preset the package ships. */
export type BuiltInPreset = (typeof BUILT_IN_PRESETS)[number];

/**
 * What a `preset` prop takes: a built-in preset, with completion, or the name
 * of one the host wrote the same way (`:where([data-fve-preset='acme'])`).
 * `string & {}` keeps the built-in names in completion while any string
 * still type-checks; a bare `string` would swallow the union.
 */
export type ViewPreset = BuiltInPreset | (string & {});

/**
 * How dense a surface sits (themes.md 2.4, D35 Q63): the record and
 * analysis tables' rows and cell padding, the view list's rows and the
 * dashboard panels' padding — never a control's height, a type size or the
 * dashboard's row. The host's call, like the preset: `data-fve-density` on
 * `<html>` for the page, or `density` on one surface. Left out, a surface
 * sits at the density its preset recommends (`--fve-preset-density`), and
 * at `default` when it recommends none.
 */
export type ViewDensity = 'compact' | 'default' | 'comfortable';
