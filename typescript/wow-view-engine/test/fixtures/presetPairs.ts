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
 * every preset to them). The pairs and the lines are the registry's
 * (`src/ui/theme/pairs.ts`), the same list Storybook's contrast matrix
 * draws in the browser; what is here is how the arithmetic reads a layer.
 */

import {
  contrastPairs,
  type Layer,
  linesOf,
  type PairKind,
} from '../../src/ui/theme/pairs';
import {
  at,
  contrast,
  type Convention,
  type Gamut,
  type HostVariables,
  type Mode,
  over,
  resolveTokens,
  type Rgba,
  tokenVariable,
} from './themeTokens';

/** One measured pair: what it is, the line it owes and what it reads. */
export interface Measured {
  name: string;
  kind: PairKind;
  line: number;
  ratio: number;
}

/** One preset in one mode, every pair measured against that preset's lines. */
export function measure(
  preset: string,
  mode: Mode,
  convention: Convention = 'semantic',
  host?: HostVariables,
  gamut?: Gamut,
): Measured[] {
  const tokens = resolveTokens(preset, mode, convention, host, gamut);
  const paint = ({ token, alpha = 1 }: Layer): Rgba => {
    const color = tokens.get(tokenVariable(token));
    if (!color) throw new Error(`${tokenVariable(token)} did not resolve`);
    return at(color, alpha);
  };
  const lines = linesOf(preset);
  return contrastPairs(mode)
    .filter(({ requires }) => !requires || tokens.has(tokenVariable(requires)))
    .map(({ name, kind, ink, ground: [bottom, ...rest] }) => ({
      name,
      kind,
      line: lines[kind],
      ratio: contrast(
        paint(ink),
        rest.reduce((under, layer) => over(paint(layer), under), paint(bottom)),
      ),
    }));
}
