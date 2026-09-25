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
