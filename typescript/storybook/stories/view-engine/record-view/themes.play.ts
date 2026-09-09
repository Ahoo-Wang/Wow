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
import type { RecordViewPlay } from './demoTypes.js';

export const playDarkRecords: RecordViewPlay = async ({
  canvasElement,
  args,
}) => {
  const canvas = within(canvasElement);
  const page = within(canvasElement.ownerDocument.body);
  await canvas.findByRole('row', { name: /ORD-202609-1001/ });
  for (const trigger of ['视图选项', '筛选模式']) {
    const button = canvas.getByRole('button', { name: trigger });
    await userEvent.click(button);
    await expect(
      getComputedStyle(await page.findByRole('menu')).colorScheme,
    ).toBe(args.appearance);
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(button).toHaveFocus());
  }
  await userEvent.click(canvas.getByRole('button', { name: '列设置' }));
  await expect(
    getComputedStyle(await page.findByRole('dialog', { name: '列设置' }))
      .colorScheme,
  ).toBe(args.appearance);
  await expect(
    await page.findByRole('checkbox', { name: /^显示\s*订单金额$/ }),
  ).toBeChecked();
  await userEvent.keyboard('{Escape}');
  await waitFor(() =>
    expect(canvas.getByRole('button', { name: '列设置' })).toHaveFocus(),
  );
};

export const playThemeSwitching: RecordViewPlay = async ({
  canvasElement,
  args,
}) => {
  const canvas = within(canvasElement);
  const page = within(canvasElement.ownerDocument.body);
  await canvas.findByRole('row', { name: /ORD-202609-1001/ });
  for (const theme of [
    args.appearance === 'light' ? 'dark' : 'light',
    args.appearance,
  ]) {
    await userEvent.click(canvas.getByRole('button', { name: '切换主题' }));
    const menu = canvas.getByRole('button', { name: '视图选项' });
    await userEvent.click(menu);
    await expect(
      getComputedStyle(await page.findByRole('menu')).colorScheme,
    ).toBe(theme);
    await userEvent.click(
      await page.findByRole('menuitem', { name: '另存为' }),
    );
    const dialog = await page.findByRole('dialog', { name: '另存为视图' });
    await waitFor(() =>
      expect(getComputedStyle(dialog).colorScheme).toBe(theme),
    );
    const visibility = within(dialog).getByRole('radiogroup', {
      name: '可见范围',
    });
    await expect(getComputedStyle(visibility).colorScheme).toBe(theme);
    await userEvent.click(
      within(visibility).getByRole('radio', { name: '个人视图' }),
    );
    await userEvent.keyboard('{ArrowDown}');
    await expect(
      within(visibility).getByRole('radio', { name: '公共视图' }),
    ).toBeChecked();
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(menu).toHaveFocus());
  }
  await expect(canvas.getByTestId('record-query-count')).toHaveTextContent(
    /^1$/,
  );
  await expect(canvas.getByTestId('record-create-count')).toHaveTextContent(
    /^0$/,
  );
};
