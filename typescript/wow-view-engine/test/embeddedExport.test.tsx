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
 * The static tier with the export on (D26 Q36).
 *
 * Read-only promises no controls on the rows, and a checkbox is one; the
 * export is a switch of its own that does not change the tier (D24 Q24). So
 * the rows stay unpicked and the export takes the whole result — the same
 * as a dashboard panel's 「导出数据…」, whose rows carry no checkboxes either.
 */
describe('a static embed with the export on', () => {
  it('draws no row checks, and exports every row', async () => {
    const createObjectURL = stubObjectUrls();
    const user = userEvent.setup();
    render(<EmbeddedView engine={engine()} instanceId="orders-1" withExport />);

    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    // Neither a row's box nor the header's 「全选」.
    expect(screen.queryAllByRole('checkbox')).toHaveLength(0);

    await user.click(await screen.findByRole('button', { name: /Export/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Export' });
    // One scope is not a choice, so the window has no radio: it says what
    // the file holds, which is every row the conditions match.
    expect(within(dialog).queryByRole('radio')).toBeNull();
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

  it('draws no row checks under the cards either', async () => {
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

    expect(await screen.findByRole('button', { name: /Export/ })).toBeDefined();
    expect(screen.queryAllByRole('checkbox')).toHaveLength(0);
  });
});
