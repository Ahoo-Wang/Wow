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
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  type ViewInstance,
} from '../src/index.js';
import { useWorkbench, type WorkbenchController } from '../src/react/index.js';
import { WorkbenchShell } from '../src/ui/WorkbenchShell.js';
import { analysisConfig, ordersDefinition, testSource } from './fixtures.js';
import { mine } from './fixtures/ui.js';

afterEach(cleanup);

const chart: ViewInstance = {
  id: 'orders-chart',
  definitionId: 'orders',
  title: 'By warehouse',
  scope: 'shared',
  revision: '1',
  config: analysisConfig(),
};

function engineWith(): ViewEngine {
  return new ViewEngine({
    definitions: [ordersDefinition()],
    store: new MemoryViewStore({ instances: [mine, chart] }),
    resolveSource: () => testSource(),
  });
}

/** The shell over a data workbench, with the controller within reach. */
function Shell({
  engine,
  hold,
}: {
  engine: ViewEngine;
  hold(workbench: WorkbenchController): void;
}) {
  const workbench = useWorkbench(engine, 'orders', {
    kinds: ['record', 'analysis'],
    instanceId: 'orders-chart',
    newView: { title: 'Untitled view' },
  });
  hold(workbench);
  return (
    <WorkbenchShell
      workbench={workbench}
      title="Orders"
      result={<div data-slot="stub-result">{workbench.runtime?.kind}</div>}
    />
  );
}

const bar = () =>
  document.querySelector<HTMLElement>('[data-slot="origin-bar"]');

describe('the origin bar', () => {
  it('names where a drilled view came from in its way back, once', async () => {
    let controller: WorkbenchController | null = null;
    render(
      <Shell
        engine={engineWith()}
        hold={workbench => {
          controller = workbench;
        }}
      />,
    );
    await screen.findByText('analysis');
    // Nothing to say about a view that was opened from the list.
    expect(bar()).toBeNull();

    act(() => {
      controller!.drill(
        [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
        'Orders · Warehouse is CN',
      );
    });
    await screen.findByText('record');

    // The line is the way back, and the way back names the origin — once
    // (2026-09-23 audit: it used to read 「Back to X · From X」, and repeat
    // the row's conditions the applied bar already shows).
    const line = bar()!;
    expect(line.getAttribute('role')).toBe('region');
    expect(line.getAttribute('aria-label')).toBe('Opened from another view');
    expect(line.textContent).toBe('Back to By warehouse');
    expect(line.textContent!.split('By warehouse')).toHaveLength(2);
    expect(line.querySelector('[data-slot="origin-condition"]')).toBeNull();
    // The title bar names the view by what it is, as the drill named it.
    expect(
      screen.getByRole('heading', {
        level: 2,
        name: 'Orders · Warehouse is CN',
      }),
    ).toBeDefined();

    fireEvent.click(
      within(line).getByRole('button', { name: 'Back to By warehouse' }),
    );
    await waitFor(() => expect(bar()).toBeNull());
    expect(
      screen.getByRole('heading', { level: 2, name: 'By warehouse' }),
    ).toBeDefined();
  });
});
