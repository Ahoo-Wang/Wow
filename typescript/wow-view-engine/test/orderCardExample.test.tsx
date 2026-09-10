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

import { afterEach, it, expect } from 'vitest';
import {
  render,
  screen,
  fireEvent,
  cleanup,
  within,
} from '@testing-library/react';
import { OrderExample } from '../examples/react/OrderExample.js';
afterEach(cleanup);
it('shows actionable business cards and refreshes the pending queue after processing', async () => {
  render(<OrderExample layout="card" scopeKey="card-business-test" />);
  const cards = await screen.findByRole('list', { name: '记录卡片' });
  expect(await within(cards).findByText('青山商店')).toBeTruthy();
  fireEvent.click(
    within(cards).getByRole('button', { name: '查看订单 DEMO-1' }),
  );
  expect(
    await screen.findByRole('dialog', { name: '订单详情 DEMO-1' }),
  ).toBeTruthy();
  fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' });
  fireEvent.click(
    within(cards).getByRole('button', { name: '处理订单 DEMO-1' }),
  );
  expect(await screen.findByText('已处理订单 DEMO-1')).toBeTruthy();
  expect(
    within(cards).queryByRole('button', { name: '处理订单 DEMO-1' }),
  ).toBeNull();
  expect(
    await within(cards).findByRole('button', { name: '处理订单 DEMO-2' }),
  ).toBeTruthy();
});
