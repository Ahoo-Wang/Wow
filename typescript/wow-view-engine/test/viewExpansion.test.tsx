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

import { cleanup, fireEvent, render } from '@testing-library/react';
import { StrictMode } from 'react';
import { afterEach, expect, it } from 'vitest';
import { ViewPage } from './fixtures/OwnedViewPage.js';
import { setup } from './fixtures/viewPage.js';

afterEach(() => {
  cleanup();
  document.body.style.removeProperty('overflow');
});

async function expandPage(doc = document) {
  const { host } = setup();
  const container = doc.body.appendChild(doc.createElement('div'));
  const view = render(
    <StrictMode>
      <ViewPage scopeKey="test-user" definitionId="orders" host={host} />
    </StrictMode>,
    { container, baseElement: container },
  );
  await view.findByRole('cell', { name: '42' });
  fireEvent.click(view.getByRole('button', { name: '展开视图' }));
  return view;
}

it.each([
  { first: 0, unmount: false },
  { first: 1, unmount: false },
  { first: 0, unmount: true },
  { first: 1, unmount: true },
])(
  'keeps scrolling locked until both expanded pages leave (first: $first, unmount: $unmount)',
  async ({ first, unmount }) => {
    document.body.style.setProperty('overflow', 'auto', 'important');
    const pages = [await expandPage(), await expandPage()];
    function close(index: number) {
      const page = pages[index];
      if (unmount) page.unmount();
      else fireEvent.click(page.getByRole('button', { name: '收起视图' }));
      expect(page.container.querySelector('[data-view-expanded]')).toBeNull();
    }
    close(first);
    expect(
      pages[1 - first].getByRole('button', { name: '收起视图' }),
    ).toBeTruthy();
    expect(document.body.style.overflow).toBe('hidden');
    close(1 - first);
    expect(document.body.style.overflow).toBe('auto');
    expect(document.body.style.getPropertyPriority('overflow')).toBe(
      'important',
    );
  },
);

it('releases all expanded pages on Escape and captures fresh overflow on the next expansion', async () => {
  const first = await expandPage();
  const second = await expandPage();
  fireEvent.keyDown(document, { key: 'Escape' });
  expect(first.queryByRole('button', { name: '收起视图' })).toBeNull();
  expect(second.queryByRole('button', { name: '收起视图' })).toBeNull();
  expect(document.body.style.overflow).toBe('');
  expect(document.body.style.getPropertyPriority('overflow')).toBe('');

  document.body.style.setProperty('overflow', 'scroll', 'important');
  fireEvent.click(first.getByRole('button', { name: '展开视图' }));
  expect(document.body.style.overflow).toBe('hidden');
  first.unmount();
  expect(document.body.style.overflow).toBe('scroll');
  expect(document.body.style.getPropertyPriority('overflow')).toBe('important');
});

it('owns the scroll lock separately for each page document', async () => {
  const frame = document.body.appendChild(document.createElement('iframe'));
  const frameDocument = frame.contentDocument!;
  document.body.style.setProperty('overflow', 'auto');
  frameDocument.body.style.setProperty('overflow', 'scroll', 'important');
  try {
    const first = await expandPage();
    const second = await expandPage(frameDocument);
    expect(document.body.style.overflow).toBe('hidden');
    expect(frameDocument.body.style.overflow).toBe('hidden');
    first.unmount();
    expect(document.body.style.overflow).toBe('auto');
    expect(frameDocument.body.style.overflow).toBe('hidden');
    second.unmount();
    expect(frameDocument.body.style.overflow).toBe('scroll');
    expect(frameDocument.body.style.getPropertyPriority('overflow')).toBe(
      'important',
    );
  } finally {
    frame.remove();
  }
});
