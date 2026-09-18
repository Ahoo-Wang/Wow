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

import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { MemoryViewStore, ViewEngine } from '../src/index.js';
import { useFilterEditor } from '../src/react/index.js';
import { FilterPanel, ViewSurface } from '../src/ui/index.js';
import { ordersDefinition, recordConfig, testSource } from './fixtures.js';

describe('popups carry the theme out of the root', () => {
  it('puts the root class and the surface theme on a popup', async () => {
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore(),
      resolveSource: () => testSource(),
    });
    const runtime = engine.create('orders', {
      title: 'T',
      scope: 'personal',
      config: recordConfig(),
    });
    function Probe() {
      return <FilterPanel filter={useFilterEditor(runtime)} />;
    }
    render(
      <ViewSurface theme="dark">
        <Probe />
      </ViewSurface>,
    );

    fireEvent.click(screen.getByRole('combobox', { name: 'Add' }));
    const popup = await screen.findByRole('listbox');
    const content = popup.closest('[data-slot="combobox-content"]');

    expect(content?.classList.contains('fve-root')).toBe(true);
    expect(content?.getAttribute('data-theme')).toBe('dark');
    // Outside the surface, as a portal is; that is the whole point.
    expect(content?.closest('[data-slot="view-surface"]')).toBeNull();
  });
});
