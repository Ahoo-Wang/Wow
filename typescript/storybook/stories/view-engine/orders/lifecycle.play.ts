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

import { expect, userEvent, waitFor, within } from 'storybook/test';
export function orderJourney(canvasElement: HTMLElement) {
  const canvas = within(canvasElement),
    page = within(canvasElement.ownerDocument.body);
  async function open(id: string) {
    await userEvent.click(
      await canvas.findByRole('button', { name: `查看订单 ${id}` }),
    );
    return page.findByRole('dialog', { name: `订单详情 ${id}` });
  }
  async function role(value: string, id?: string) {
    const current = page.queryByRole('dialog');
    if (
      id &&
      current?.getAttribute('aria-labelledby') &&
      within(current).queryByLabelText('处理岗位')
    ) {
      const input = within(current).getByLabelText('处理岗位');
      await waitFor(() => expect(input).toBeEnabled());
      await userEvent.selectOptions(input, value);
      await expect(page.getByRole('dialog')).toHaveAccessibleName(
        `订单详情 ${id}`,
      );
      return;
    }
    const close = page.queryByRole('button', { name: '关闭详情' });
    if (close) {
      await waitFor(() => expect(close).toBeEnabled());
      await userEvent.click(close);
    }
    await userEvent.selectOptions(canvas.getByLabelText('演示岗位'), value);
    if (id) await open(id);
  }
  async function action(label: string, fill?: () => Promise<void>) {
    const details = page.getByRole('dialog');
    const button = within(details).getByRole('button', {
      name: label,
      exact: true,
    });
    await waitFor(() => expect(button).toBeEnabled());
    await userEvent.click(button);
    if (fill) await fill();
    await userEvent.click(
      page.getByRole('button', { name: `确认${label}`, exact: true }),
    );
    await waitFor(() => {
      const current = page.getByRole('dialog');
      const invalid = Array.from(
        current.querySelectorAll<HTMLInputElement>('input:invalid'),
      )
        .map(i => `${i.name}: ${i.validationMessage} (${i.value})`)
        .join('; ');
      const title = current.ownerDocument.getElementById(
        current.getAttribute('aria-labelledby') ?? '',
      )?.textContent;
      if (!title?.startsWith('订单详情')) {
        const inputs = Array.from(
          current.querySelectorAll<HTMLInputElement>('input'),
        )
          .map(i => `${i.name}=${i.value}`)
          .join('; ');
        throw new Error(
          `${label} did not finish: invalid=${invalid}; inputs=${inputs}; alert=${current.querySelector('[role=alert]')?.textContent ?? ''}`,
        );
      }
    });
    await waitFor(() =>
      expect(page.getByRole('button', { name: '关闭详情' })).toBeEnabled(),
    );
  }
  async function reference(value: string) {
    await userEvent.type(page.getByRole('textbox', { name: '凭证号' }), value);
  }
  return { canvas, page, open, role, action, reference };
}
export async function completeOrder({
  canvasElement,
}: {
  canvasElement: HTMLElement;
}) {
  const { canvas, page, role, action, reference } = orderJourney(canvasElement);
  await userEvent.click(
    await canvas.findByRole('button', { name: '创建订单' }),
  );
  await expect(
    await page.findByRole('dialog', { name: '创建销售订单' }),
  ).toBeVisible();
  await expect(page.getByLabelText('订单合计')).toHaveTextContent('2,400.00');
  await userEvent.click(page.getByRole('button', { name: '确认创建' }));
  const id = 'SO-202609-1019';
  await page.findByRole('dialog', { name: `订单详情 ${id}` });
  await action('提交审核');
  await role('manager', id);
  await action('审核通过');
  await role('finance', id);
  await action('登记收款', () => reference('BANK-NEW'));
  await role('manager', id);
  await action('放行交付');
  await role('delivery', id);
  await action('登记备货');
  await action('登记发货', async () => {
    await userEvent.type(
      page.getByRole('textbox', { name: '运单号' }),
      'SF-NEW',
    );
  });
  await action('签收与拒收');
  await role('finance', id);
  await action('登记开票', () => reference('INV-NEW'));
  await action('结算核对');
  await role('manager', id);
  await action('关闭订单');
  await expect(
    within(page.getByRole('dialog')).getByText('已关闭', { exact: true }),
  ).toBeVisible();
  await role('sales');
  await userEvent.click(canvas.getByRole('button', { name: '重置演示' }));
  await waitFor(() =>
    expect(
      canvas.queryByRole('button', { name: `查看订单 ${id}` }),
    ).not.toBeInTheDocument(),
  );
}
