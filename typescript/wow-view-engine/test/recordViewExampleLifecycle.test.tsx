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

import { afterEach, it, expect, vi } from 'vitest';
import {
  render,
  screen,
  fireEvent,
  cleanup,
  waitFor,
  within,
} from '@testing-library/react';
import { RecordViewExample } from '../../../stories/docs/RecordViewExample.js';
afterEach(() => {
  cleanup();
  localStorage.clear();
  vi.unstubAllGlobals();
});
it('rebuilds the document example host when persistence is enabled or disabled', async () => {
  vi.stubGlobal(
    'navigator',
    Object.assign(Object.create(navigator), {
      locks: {
        request: async (
          _name: string,
          _options: unknown,
          operation: () => unknown,
        ) => operation(),
      },
    }),
  );
  const view = render(<RecordViewExample layout="card" />);
  await within(
    await screen.findByRole('list', { name: '记录卡片' }),
  ).findAllByText('金额');
  view.rerender(<RecordViewExample layout="card" persistViews />);
  fireEvent.click(await screen.findByRole('button', { name: '卡片设置' }));
  fireEvent.click(await screen.findByRole('button', { name: '移除摘要 1' }));
  fireEvent.click(screen.getByRole('button', { name: '应用设置' }));
  fireEvent.click(
    await screen.findByRole('button', { name: '保存', exact: true }),
  );
  await waitFor(() =>
    expect(
      screen.getByRole('status', { name: '保存状态' }).textContent,
    ).toContain('视图已保存'),
  );
  view.rerender(<RecordViewExample layout="card" persistViews={false} />);
  await within(
    await screen.findByRole('list', { name: '记录卡片' }),
  ).findAllByText('金额');
  fireEvent.click(screen.getByRole('button', { name: '卡片设置' }));
  fireEvent.click(await screen.findByRole('button', { name: '移除摘要 1' }));
  fireEvent.click(screen.getByRole('button', { name: '应用设置' }));
  expect(
    screen.queryByRole('button', { name: '保存', exact: true }),
  ).toBeNull();
  view.rerender(<RecordViewExample layout="card" persistViews />);
  await screen.findByRole('list', { name: '记录卡片' });
  await screen.findByRole('heading', { name: 'ORDER-001' });
  expect(
    within(screen.getByRole('list', { name: '记录卡片' })).queryByText('金额', {
      exact: true,
    }),
  ).toBeNull();
  expect(screen.queryByRole('alert')).toBeNull();
});
