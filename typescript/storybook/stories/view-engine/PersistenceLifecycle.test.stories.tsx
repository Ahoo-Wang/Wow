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

import { useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { expect, userEvent, within, waitFor } from 'storybook/test';
import { RecordViewExample } from '../docs/RecordViewExample.js';
import { ProductCatalogExample } from '../../packages/view-engine/examples/react/catalog/ProductCatalogExample.js';

function PersistenceToggle({ catalog = false }: { catalog?: boolean }) {
  const [persist, setPersist] = useState(false);
  return (
    <>
      <button onClick={() => setPersist(value => !value)}>切换持久化</button>
      {catalog ? (
        <ProductCatalogExample persistViews={persist} />
      ) : (
        <RecordViewExample layout="card" persistViews={persist} />
      )}
    </>
  );
}
const meta = {
  title: 'View Engine/专项场景/视图与运行时/持久化生命周期/回归',
  component: PersistenceToggle,
  tags: ['!dev', '!autodocs', 'test'],
  beforeEach: async ({ args }) => {
    const key = `fve:views:${JSON.stringify(args.catalog ? ['catalog-v1', 'product-catalog'] : ['card-example', 'first-orders'])}`;
    await new Promise<void>((resolve, reject) => {
      const request = indexedDB.open('fve-view-state', 1);
      request.onupgradeneeded = () =>
        request.result.createObjectStore('states');
      request.onerror = () => reject(request.error);
      request.onsuccess = () => {
        const db = request.result;
        const transaction = db.transaction('states', 'readwrite');
        transaction.objectStore('states').put(null, key);
        transaction.oncomplete = () => {
          db.close();
          resolve();
        };
        transaction.onabort = () => {
          db.close();
          reject(transaction.error);
        };
      };
    });
  },
} satisfies Meta<typeof PersistenceToggle>;
export default meta;
type Story = StoryObj<typeof meta>;
export const Minimal: Story = {
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement),
      page = within(canvasElement.ownerDocument.body);
    await canvas.findByRole('list', { name: '记录卡片' });
    await userEvent.click(canvas.getByRole('button', { name: '切换持久化' }));
    await userEvent.click(
      await canvas.findByRole('button', { name: '卡片设置' }),
    );
    await userEvent.click(
      await page.findByRole('button', { name: '移除摘要 1' }),
    );
    await userEvent.click(page.getByRole('button', { name: '应用设置' }));
    await userEvent.click(
      canvas.getByRole('button', { name: '保存', exact: true }),
    );
    await waitFor(() =>
      expect(
        canvas.getByRole('status', { name: '保存状态' }),
      ).toHaveTextContent('视图已保存'),
    );
    await userEvent.click(canvas.getByRole('button', { name: '切换持久化' }));
    await expect(
      (await canvas.findAllByText('金额', { exact: true })).length,
    ).toBeGreaterThan(0);
    await expect(
      canvas.queryByRole('button', { name: '保存', exact: true }),
    ).toBeNull();
    await userEvent.click(canvas.getByRole('button', { name: '切换持久化' }));
    await canvas.findByRole('heading', { name: 'SO-202609-1001' });
    await expect(
      within(canvas.getByRole('list', { name: '记录卡片' })).queryByText(
        '金额',
        { exact: true },
      ),
    ).toBeNull();
    await expect(canvas.queryByRole('alert')).toBeNull();
  },
};
export const Catalog: Story = {
  args: { catalog: true },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement),
      page = within(canvasElement.ownerDocument.body);
    await userEvent.click(
      await canvas.findByRole('button', { name: '收藏商品 SKU-001' }),
    );
    await expect(
      canvas.getByRole('button', { name: '收藏商品 SKU-001' }),
    ).toHaveAttribute('aria-pressed', 'true');
    await userEvent.click(canvas.getByRole('button', { name: '切换持久化' }));
    await canvas.findByRole('button', { name: '重新打开已保存视图' });
    await canvas.findByRole('button', { name: '排序：默认' });
    await userEvent.click(canvas.getByRole('button', { name: '卡片设置' }));
    await userEvent.click(
      await page.findByRole('button', { name: '移除摘要 2' }),
    );
    await userEvent.click(page.getByRole('button', { name: '应用设置' }));
    await userEvent.click(
      canvas.getByRole('button', { name: '保存', exact: true }),
    );
    await waitFor(() =>
      expect(
        canvas.getByRole('status', { name: '保存状态' }),
      ).toHaveTextContent('视图已保存'),
    );
    await userEvent.click(
      canvas.getByRole('button', { name: '重新打开已保存视图' }),
    );
    await expect(
      await canvas.findByRole('button', { name: '收藏商品 SKU-001' }),
    ).toHaveAttribute('aria-pressed', 'true');
    await expect(canvas.queryByText('库存', { exact: true })).toBeNull();
    await userEvent.click(canvas.getByRole('button', { name: '切换持久化' }));
    await canvas.findByRole('button', { name: '收藏商品 SKU-001' });
    await expect(
      canvas.queryByRole('button', { name: '重新打开已保存视图' }),
    ).toBeNull();
    await expect(canvas.queryByRole('alert')).toBeNull();
  },
};
