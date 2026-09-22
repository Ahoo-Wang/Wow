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
  it('says where a drilled view came from, and takes the user back', async () => {
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
      controller!.drill([{ field: 'warehouse', operator: 'EQ', value: 'CN' }]);
    });
    await screen.findByText('record');

    // The line: the way back, the origin's name, and the row's condition in
    // the applied bar's own words.
    const line = bar()!;
    expect(line.getAttribute('role')).toBe('region');
    expect(within(line).getByText('From By warehouse')).toBeDefined();
    const conditions = [
      ...line.querySelectorAll('[data-slot="origin-condition"]'),
    ].map(badge => badge.textContent);
    expect(conditions).toEqual(['Warehouse is CN']);
    // The title bar names the drilled view as unsaved, like any new view.
    expect(
      screen.getByRole('heading', { level: 2, name: 'Untitled view' }),
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
