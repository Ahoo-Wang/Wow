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
} from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { RecordWorkbench } from '../src/ui/index.js';
import { testSource } from './fixtures.js';
import { setup } from './fixtures/ui.js';

afterEach(cleanup);

/**
 * The whole chain of an export, from the menu to the blob: the reading each
 * cell has, the serializer, the file name and what the browser is handed.
 *
 * The menu's own items and their counts are `test/resultToolbar.test.tsx`,
 * and the fetching is `test/exportRows.test.ts`; this is the one place all of
 * them are the same export.
 */
describe('RecordWorkbench export', () => {
  /** The browser's half, which jsdom has none of. */
  function stubObjectUrls() {
    const createObjectURL = vi.fn(() => 'blob:orders');
    const revokeObjectURL = vi.fn();
    Object.assign(URL, { createObjectURL, revokeObjectURL });
    return { createObjectURL, revokeObjectURL };
  }

  it('hands the page over as a CSV named for the view and the day', async () => {
    const { createObjectURL, revokeObjectURL } = stubObjectUrls();
    const onExported = vi.fn();
    const { engine } = setup(testSource());
    render(
      <RecordWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
        onExported={onExported}
      />,
    );
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));

    fireEvent.click(screen.getByRole('button', { name: 'Export' }));
    fireEvent.click(
      await screen.findByRole('menuitem', { name: 'Export this page (2)' }),
    );

    await waitFor(() => expect(onExported).toHaveBeenCalledTimes(1));
    const file = onExported.mock.calls[0][0] as {
      name: string;
      text: string;
      rows: number;
      scope: string;
    };
    expect(file.scope).toBe('page');
    expect(file.rows).toBe(2);
    expect(file.name).toMatch(/^Mine-\d{4}-\d{2}-\d{2}\.csv$/);
    // The header is the columns the table draws, by their labels, and every
    // value is the reading the cell above it had.
    expect(file.text).toBe('﻿Order,Amount\r\no-1,10\r\no-2,20\r\n');
    // And it really went to the browser: one blob, made as a CSV, and its
    // URL released again rather than held for the session.
    expect(createObjectURL).toHaveBeenCalledTimes(1);
    expect((createObjectURL.mock.calls[0] as unknown as [Blob])[0].type).toBe(
      'text/csv;charset=utf-8',
    );
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:orders');
  });
});
