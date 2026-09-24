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

import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  type ViewInstance,
} from '../src/index.js';
import { DataWorkbench } from '../src/ui/index.js';
import { analysisConfig, ordersDefinition, testSource } from './fixtures.js';

afterEach(cleanup);

/**
 * The visualization panel on a narrow screen (2026-09-23 audit): below `md`
 * the shell stacks its column over the work area, and the panel — ten tiles
 * and a button — took a phone's whole first screen, the chart it configures
 * pushed below it. There it is a drawer from the bottom edge (the registry's
 * sheet), over the page; dismissing it is the panel's own way back. The
 * sizes are the browser story's (「分析工作台/回归」 `VisualizeOnAPhone`).
 */

/** The width every element reports, as `workbenchShell.test.tsx` lays out. */
const laidOutAt = (width: number) =>
  vi
    .spyOn(HTMLElement.prototype, 'getBoundingClientRect')
    .mockReturnValue({ width, height: 600 } as DOMRect);

async function open() {
  const instance: ViewInstance = {
    id: 'orders-1',
    definitionId: 'orders',
    title: 'By warehouse',
    scope: 'personal',
    revision: '1',
    config: analysisConfig({ layout: 'chart' }),
  };
  const engine = new ViewEngine({
    definitions: [ordersDefinition()],
    store: new MemoryViewStore({ instances: [instance] }),
    resolveSource: () => testSource(),
  });
  render(
    <DataWorkbench
      engine={engine}
      definitionId="orders"
      instanceId="orders-1"
      kinds={['analysis']}
    />,
  );
  return screen.findByRole('button', { name: 'Visualize' });
}

const panel = () => document.querySelector('[data-slot="view-panel"]');

describe('the visualization panel on a narrow screen', () => {
  it('opens as a drawer over the page, the keyboard on its heading', async () => {
    laidOutAt(375);
    fireEvent.click(await open());

    const drawer = await screen.findByRole('dialog', { name: 'Visualization' });
    expect(drawer.getAttribute('data-slot')).toBe('view-panel');
    expect(drawer.getAttribute('data-side')).toBe('bottom');
    expect(drawer.hasAttribute('data-drawer')).toBe(true);
    // Not a block on top of the result: the only panel is the drawer.
    expect(document.querySelectorAll('[data-slot="view-panel"]')).toHaveLength(
      1,
    );
    await waitFor(() => expect(document.activeElement?.tagName).toBe('H2'));
    expect(within(drawer).getByRole('radiogroup')).toBeDefined();
  });

  it('closes on Escape, handing the keyboard back to Visualize', async () => {
    laidOutAt(375);
    const visualize = await open();
    fireEvent.click(visualize);
    const drawer = await screen.findByRole('dialog', { name: 'Visualization' });

    fireEvent.keyDown(drawer, { key: 'Escape' });
    await waitFor(() => expect(panel()).toBeNull());
    await waitFor(() =>
      expect(document.activeElement).toBe(
        screen.getByRole('button', { name: 'Visualize' }),
      ),
    );
  });

  it('stays a column beside the view where there is room', async () => {
    laidOutAt(1280);
    fireEvent.click(await open());
    await waitFor(() => expect(panel()).not.toBeNull());
    expect(panel()!.tagName).toBe('ASIDE');
    expect(screen.queryByRole('dialog')).toBeNull();
  });
});
