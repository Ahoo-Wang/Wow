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

import { expect, userEvent, waitFor } from 'storybook/test';

export async function keyboardOrder(handle: HTMLElement, key: string) {
  handle.focus();
  await userEvent.keyboard('[Space]');
  await waitFor(() =>
    expect(handle.closest('li')).toHaveAttribute('data-dnd-dragging'),
  );
  await userEvent.keyboard(`[${key}]`);
  await waitFor(() =>
    expect(
      handle.ownerDocument.querySelector('[data-drop-target]'),
    ).toBeInTheDocument(),
  );
  await userEvent.keyboard('[Space]');
  await waitFor(() =>
    expect(
      handle.ownerDocument.querySelector('[data-dnd-placeholder]'),
    ).not.toBeInTheDocument(),
  );
  await waitFor(() => expect(handle).toHaveFocus());
}
export async function pointerOrder(
  handle: HTMLElement,
  target: HTMLElement,
  allowed = true,
) {
  const pointer = userEvent.setup();
  const origin = handle.getBoundingClientRect(),
    destination = target.getBoundingClientRect();
  await pointer.pointer({
    target: handle,
    keys: '[MouseLeft>]',
    coords: {
      x: origin.left + origin.width / 2,
      y: origin.top + origin.height / 2,
    },
  });
  await pointer.pointer({
    target: handle.ownerDocument.documentElement,
    coords: {
      x: origin.left + origin.width / 2 + 12,
      y: origin.top + origin.height / 2,
    },
  });
  await waitFor(() =>
    expect(handle.closest('li')).toHaveAttribute('data-dnd-dragging'),
  );
  await pointer.pointer({
    target: handle.ownerDocument.documentElement,
    coords: {
      x: destination.left + destination.width / 2,
      y: destination.top + destination.height / 2,
    },
  });
  if (allowed)
    await waitFor(() => expect(target).toHaveAttribute('data-drop-target'));
  await pointer.pointer({ keys: '[/MouseLeft]' });
  await waitFor(() =>
    expect(
      handle.ownerDocument.querySelector('[data-dnd-placeholder]'),
    ).not.toBeInTheDocument(),
  );
}
