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
} from '@testing-library/react';
import { ProductCatalogExample } from '../examples/react/catalog/ProductCatalogExample.js';
afterEach(() => {
  cleanup();
  localStorage.clear();
  vi.unstubAllGlobals();
});
it('switches persistence both ways, reopens the view and retains product records', async () => {
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
  const view = render(<ProductCatalogExample />);
  const favorite = await screen.findByRole('button', {
    name: '收藏商品 SKU-001',
  });
  fireEvent.click(favorite);
  await waitFor(() =>
    expect(
      screen
        .getByRole('button', { name: '收藏商品 SKU-001' })
        .getAttribute('aria-pressed'),
    ).toBe('true'),
  );
  view.rerender(<ProductCatalogExample persistViews />);
  await screen.findByRole('button', { name: '重新打开已保存视图' });
  await screen.findByRole('button', { name: '排序：默认' });
  fireEvent.click(await screen.findByRole('button', { name: '卡片设置' }));
  fireEvent.click(await screen.findByRole('button', { name: '移除摘要 2' }));
  fireEvent.click(screen.getByRole('button', { name: '应用设置' }));
  fireEvent.click(
    await screen.findByRole('button', { name: '保存', exact: true }),
  );
  await waitFor(() =>
    expect(
      screen.getByRole('status', { name: '保存状态' }).textContent,
    ).toContain('视图已保存'),
  );
  // Reopening requires a host which supplies persisted metadata.
  fireEvent.click(screen.getByRole('button', { name: '重新打开已保存视图' }));
  expect(
    await screen.findByRole('button', { name: '收藏商品 SKU-001' }),
  ).toBeTruthy();
  expect(
    screen
      .getByRole('button', { name: '收藏商品 SKU-001' })
      .getAttribute('aria-pressed'),
  ).toBe('true');
  expect(screen.queryByText('库存', { exact: true })).toBeNull();
  expect(screen.queryByRole('alert')).toBeNull();
  view.rerender(<ProductCatalogExample persistViews={false} />);
  await screen.findByRole('button', { name: '收藏商品 SKU-001' });
  expect(
    screen.queryByRole('button', { name: '重新打开已保存视图' }),
  ).toBeNull();
  expect(screen.queryByRole('alert')).toBeNull();
});
