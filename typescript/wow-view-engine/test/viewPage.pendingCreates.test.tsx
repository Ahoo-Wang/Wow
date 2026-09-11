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
} from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { ViewPageContent } from '../src/view/ViewPageContent.js';
import { ViewServiceError } from '../src/record/viewServiceContract.js';
import { instance, setup } from './engine/fixtures.js';

afterEach(cleanup);
it('offers recovery without restoring a removed source to the visible view list', async () => {
  const list = vi
    .fn()
    .mockResolvedValue({ instances: [instance()], defaultInstanceId: 'mine' });
  const create = vi
    .fn()
    .mockRejectedValueOnce(
      new ViewServiceError('UNKNOWN_OUTCOME', 'response lost'),
    )
    .mockImplementation(async value => ({
      ...value,
      id: 'created',
      revision: 'r1',
    }));
  const { engine } = setup({
    instances: undefined,
    host: { instance: { list, create } },
  });
  await engine.load();
  const view = render(<ViewPageContent engine={engine} />);
  try {
    await act(async () => {
      await expect(
        engine.saveAs({ title: 'Copy', scope: { type: 'personal' } }),
      ).rejects.toThrow('response lost');
      list.mockResolvedValue({ instances: [], defaultInstanceId: null });
      await engine.load();
    });
    expect(
      await screen.findByRole('alert', { name: '待核对另存：mine' }),
    ).toBeTruthy();
    expect(engine.getSnapshot().instanceIds).toEqual([]);
    fireEvent.click(screen.getByRole('button', { name: '核对另存结果' }));
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: '核对另存结果' })).toBeNull(),
    );
    expect(engine.getSnapshot().instanceIds).toEqual(['created']);
    expect(create).toHaveBeenCalledTimes(2);
  } finally {
    view.unmount();
    engine.dispose();
  }
});
