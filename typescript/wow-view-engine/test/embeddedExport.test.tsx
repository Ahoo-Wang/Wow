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
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MemoryViewStore, ViewEngine } from '../src/index.js';
import type { ViewInstance } from '../src/index.js';
import { EmbeddedView } from '../src/ui/index.js';
import { ordersDefinition, recordConfig, testSource } from './fixtures.js';
import { mine } from './fixtures/ui.js';

afterEach(cleanup);

/** The browser's half of a download, which jsdom has none of. */
function stubObjectUrls() {
  const createObjectURL = vi.fn((blob: Blob) => {
    void blob;
    return 'blob:embed';
  });
  Object.assign(URL, { createObjectURL, revokeObjectURL: vi.fn() });
  return createObjectURL;
}

function engine(): ViewEngine {
  const instance: ViewInstance = { ...mine, config: recordConfig() };
  return new ViewEngine({
    definitions: [ordersDefinition()],
    store: new MemoryViewStore({ instances: [instance] }),
    resolveSource: () => testSource(),
  });
}

/**
 * An embed's export (D14): the tier is the ceiling and `withExport` opts in
 * within it (D36, amending D24 Q24 and D26 Q36). The static tier has no
 * controls, so the switch has no effect there — no button, no row checks;
 * the interactive tier draws the export in the first row, rows picked with
 * it, and exports every row when none is picked.
 */
describe('an embed with the export on', () => {
  it('exports every row from the interactive tier when none is picked', async () => {
    const createObjectURL = stubObjectUrls();
    const user = userEvent.setup();
    render(
      <EmbeddedView
        engine={engine()}
        instanceId="orders-1"
        interaction="interactive"
        withExport
      />,
    );

    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    await user.click(await screen.findByRole('button', { name: /Export/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Export' });
    expect(dialog.textContent).toContain('2 records');

    await user.click(within(dialog).getByRole('button', { name: 'Export' }));
    await waitFor(() => expect(createObjectURL).toHaveBeenCalledTimes(1));
    const blob = createObjectURL.mock.calls[0][0];
    // A header and the two rows.
    expect((await blob.text()).trim().split('\r\n')).toHaveLength(3);
  });

  it('keeps the row checks in the interactive tier, for the picked scope', async () => {
    render(
      <EmbeddedView
        engine={engine()}
        instanceId="orders-1"
        interaction="interactive"
        withExport
      />,
    );

    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    // The header's box and one per row.
    expect(screen.getAllByRole('checkbox')).toHaveLength(3);
  });

  it('draws neither the export nor a row check in the static tier, table or cards', async () => {
    render(<EmbeddedView engine={engine()} instanceId="orders-1" withExport />);
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    expect(screen.queryByRole('button', { name: /Export/ })).toBeNull();
    expect(screen.queryAllByRole('checkbox')).toHaveLength(0);
    cleanup();

    const instance: ViewInstance = {
      ...mine,
      config: recordConfig({ layout: 'card' }),
    };
    render(
      <EmbeddedView
        engine={
          new ViewEngine({
            definitions: [ordersDefinition()],
            store: new MemoryViewStore({ instances: [instance] }),
            resolveSource: () => testSource(),
          })
        }
        instanceId="orders-1"
        withExport
      />,
    );
    await waitFor(() =>
      expect(
        document.querySelector(
          '[data-slot="record-cards"], [data-slot="card"]',
        ),
      ).not.toBeNull(),
    );
    expect(screen.queryByRole('button', { name: /Export/ })).toBeNull();
    expect(screen.queryAllByRole('checkbox')).toHaveLength(0);
  });
});
