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

import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { hostSource, readable } from './hostSource.js';

const file = (name: string) =>
  readFileSync(new URL(name, import.meta.url), 'utf8');

describe('hostSource: what 「Show code」 shows', () => {
  it('drops the licence header', () => {
    expect(readable(file('./hostSource.ts'))).toMatch(/^\/\*\n \* What/);
  });

  it('keeps a scene’s imports and components, and nothing of its catalogue entry', () => {
    const shown = hostSource([
      'RetailOrders.stories.tsx',
      file('./RetailOrders.stories.tsx'),
    ]);
    expect(shown).toMatch(/^\/\/ RetailOrders\.stories\.tsx\n\nimport /);
    expect(shown).toContain('function OrderWorkbench(');
    expect(shown).toContain("from '@ahoo-wang/wow-view-engine/ui';");
    for (const catalogue of [
      'const description',
      'const meta',
      'HOST_CODE',
      '?raw',
      'hostSource',
      '@storybook/',
      'export default',
    ])
      expect(shown).not.toContain(catalogue);
  });

  it('shows several files one after another, each under its name', () => {
    const shown = hostSource(
      ['OpsDaily.stories.tsx', file('./OpsDaily.stories.tsx')],
      ['retail/RetailHost.tsx', file('./retail/RetailHost.tsx')],
    );
    expect(shown).toContain('\n\n// retail/RetailHost.tsx\n\n');
    expect(shown).toContain('export function RetailBoardScene(');
  });
});
