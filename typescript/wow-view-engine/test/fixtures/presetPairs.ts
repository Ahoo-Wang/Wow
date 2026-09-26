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
 * The pairs the surface paints, measured by arithmetic on one preset in one
 * mode and change convention (`test/presetContrast.test.ts`, which holds
 * every preset to them) — or on a host's own preset, passed in
 * `placement.extra`. The pairs and the lines are the registry's
 * (`src/ui/theme/pairs.ts`), the same list Storybook's contrast matrix
 * draws in the browser; the arithmetic is theme-check's
 * (`theme-check/resolve.ts`).
 */

import { RESOLVER } from './themeTokens';

export type { Measured } from '../../theme-check/resolve';

/** One preset in one mode, every pair measured against that preset's lines. */
export const measure = RESOLVER.measure;
