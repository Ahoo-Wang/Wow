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
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  ViewStoreError,
  type Issue,
} from '../src/index.js';
import { RecordWorkbench } from '../src/ui/index.js';
import { ordersDefinition, testSource } from './fixtures.js';
import { mine } from './fixtures/ui.js';

afterEach(cleanup);

function setup() {
  const store = new MemoryViewStore({ instances: [mine] });
  const engine = new ViewEngine({
    definitions: [ordersDefinition()],
    store,
    resolveSource: () => testSource(),
  });
  return { engine, store };
}

const sidebar = () => screen.getByRole('navigation', { name: 'Orders' });
const statusLine = () =>
  document.querySelector<HTMLElement>('[data-slot="status-line"]');

/**
 * What the screen does when the store stops answering for the list (F-05).
 * Before this a failed reload blanked the sidebar, moved the default, closed
 * the runtime riding on it and lost the draft without a question — and the
 * reason, like failed preferences and the definition's own findings, was
 * reported to `onIssue` and to nobody on screen.
 */
describe('when the list cannot be read', () => {
  it('keeps the open view and its draft when a reload fails after a save', async () => {
    const { engine, store } = setup();
    render(
      <RecordWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    const runtime = engine.openRuntimes()[0];

    // The save lands; the reloads it triggers — the engine's notification
    // and the workbench's own — do not, until the store is back.
    const read = store.list.bind(store);
    let down = true;
    vi.spyOn(store, 'list').mockImplementation((...args) =>
      down
        ? Promise.reject(new ViewStoreError('UNAVAILABLE', 'offline'))
        : read(...args),
    );
    act(() => runtime.edit({ pageSize: 30 }));
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() =>
      expect(within(sidebar()).getByRole('status')).toBeDefined(),
    );

    // Still the same view, still open, rows still here; the list still
    // names it; the failure is said in the sidebar with the way to ask again.
    expect(engine.openRuntimes()[0]).toBe(runtime);
    expect(
      screen.getByRole('heading', { level: 2, name: 'Mine' }),
    ).toBeDefined();
    expect(
      within(sidebar()).getByRole('button', { name: /Mine/ }),
    ).toBeDefined();
    expect(within(sidebar()).getByRole('status').textContent).toContain(
      'could not be loaded',
    );

    // Asking again reads the list the store has now, and the line goes.
    down = false;
    fireEvent.click(
      within(sidebar()).getByRole('button', { name: 'Reload list' }),
    );
    await waitFor(() =>
      expect(within(sidebar()).queryByRole('status')).toBeNull(),
    );
  });

  it('says why the list is empty, and offers to read it again', async () => {
    const { engine, store } = setup();
    vi.spyOn(store, 'list').mockRejectedValueOnce(
      new ViewStoreError('UNAVAILABLE', 'offline'),
    );
    render(
      <RecordWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );

    // The declared view is listed whatever the store said, so the sidebar
    // is short rather than empty, and says so above the view that is here.
    await waitFor(() =>
      expect(within(sidebar()).getByRole('status')).toBeDefined(),
    );
    expect(
      within(sidebar()).getByRole('button', { name: /All orders/ }),
    ).toBeDefined();
    fireEvent.click(
      within(sidebar()).getByRole('button', { name: 'Reload list' }),
    );
    await waitFor(() =>
      expect(
        within(sidebar()).getByRole('button', { name: /Mine/ }),
      ).toBeDefined(),
    );
  });

  it('says in the status line that the preferences did not load', async () => {
    const { engine, store } = setup();
    vi.spyOn(store, 'getPreferences').mockRejectedValueOnce(
      new ViewStoreError('FORBIDDEN', 'nope'),
    );
    render(
      <RecordWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));

    await waitFor(() =>
      expect(statusLine()?.textContent).toContain(
        'preferences could not be loaded',
      ),
    );
    // A warning: the list works, in the server's order.
    expect(statusLine()?.querySelector('[data-tone="warning"]')).not.toBeNull();
  });

  it("puts the definition's own findings in the status line", async () => {
    const { engine } = setup();
    const found: Issue = {
      code: 'view.definition.invalid',
      severity: 'warning',
      path: [],
      params: { id: 'orders', issues: 1 },
    };
    vi.spyOn(engine, 'definitionIssues').mockReturnValue([found]);
    render(
      <RecordWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));

    await waitFor(() =>
      expect(statusLine()?.textContent).toContain('cannot be opened'),
    );
  });
});
