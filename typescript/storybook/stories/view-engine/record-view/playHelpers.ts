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

import { userEvent, within } from 'storybook/test';

export async function chooseInstance(
  canvasElement: HTMLElement,
  title: string,
) {
  const canvas = within(canvasElement);
  const collapse = canvas.queryByRole('button', { name: '收起视图列表' });
  if (collapse) await userEvent.click(collapse);
  await userEvent.click(canvas.getByRole('combobox', { name: '选择视图实例' }));
  await userEvent.click(
    await within(canvasElement.ownerDocument.body).findByRole('option', {
      name: title,
    }),
  );
}
