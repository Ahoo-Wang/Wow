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

import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import {
  RegistryClient,
  type RegistryDataset,
  type Send,
} from '../registry.ts';
import AdminPage from './AdminPage.tsx';

const ORDERS: RegistryDataset = {
  id: 'analytics.orders',
  table: 'orders',
  document: { fields: { id: {}, phone: {} } },
  reviews: { phone: { level: 'CONFIDENTIAL' } },
  published: { title: 'Orders' },
  publishedVersion: 2,
};

function setUp(refuse?: string) {
  const calls: unknown[][] = [];
  const send: Send = async (method, path, body) => {
    calls.push([method, path, body]);
    if (path.endsWith('/snapshot/list/state'))
      return [
        ORDERS,
        {
          ...ORDERS,
          id: 'analytics.draft',
          published: null,
          publishedVersion: 0,
        },
      ];
    if (refuse) throw new Error(refuse);
    return null;
  };
  render(<AdminPage client={new RegistryClient(send)} />);
  return calls;
}

describe('AdminPage', () => {
  it("lists the registry's datasets with their state", async () => {
    setUp();
    expect(await screen.findByText('已发布 v2')).toBeInTheDocument();
    expect(screen.getByText('草稿')).toBeInTheDocument();
  });

  it('drafts a dataset from a table', async () => {
    const calls = setUp();
    fireEvent.change(screen.getByLabelText('数据集名'), {
      target: { value: 'analytics.wide' },
    });
    fireEvent.change(screen.getByLabelText('表'), {
      target: { value: 'bi.wide' },
    });
    fireEvent.click(screen.getByRole('button', { name: '从表起草' }));
    await waitFor(() =>
      expect(calls).toContainEqual([
        'POST',
        '/dataset-registry/dataset/analytics.wide/draft',
        { table: 'bi.wide' },
      ]),
    );
  });

  it("says the server's refusal", async () => {
    setUp('Every string field must be reviewed.');
    fireEvent.click(
      (await screen.findAllByRole('button', { name: '发布' }))[1],
    );
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Every string field must be reviewed.',
    );
  });

  it('revises the declaration and reviews each field', async () => {
    const calls = setUp();
    fireEvent.click(
      await screen.findByRole('button', { name: 'analytics.orders' }),
    );
    fireEvent.change(screen.getByLabelText('id 的敏感等级'), {
      target: { value: 'DISPLAY' },
    });
    fireEvent.click(screen.getByRole('button', { name: '提交审核' }));
    fireEvent.click(screen.getByRole('button', { name: '保存修订' }));
    await waitFor(() => {
      expect(calls).toContainEqual([
        'PUT',
        '/dataset-registry/dataset/analytics.orders/sensitivity',
        {
          fields: {
            id: { level: 'DISPLAY' },
            phone: { level: 'CONFIDENTIAL' },
          },
        },
      ]);
      expect(calls).toContainEqual([
        'PUT',
        '/dataset-registry/dataset/analytics.orders/revise',
        { document: ORDERS.document },
      ]);
    });
  });

  it('refuses a declaration that is no JSON', async () => {
    setUp();
    fireEvent.click(
      await screen.findByRole('button', { name: 'analytics.orders' }),
    );
    fireEvent.change(screen.getByLabelText(/声明/), { target: { value: '{' } });
    fireEvent.click(screen.getByRole('button', { name: '保存修订' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '声明不是合法的 JSON',
    );
  });
});
