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

import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { StrictMode } from 'react';
import { afterEach, expect, it, vi } from 'vitest';
import { ViewPage } from './fixtures/OwnedViewPage.js';
import { definition, setup } from './fixtures/viewPage.js';

afterEach(cleanup);

it('does not load data without an explicit access scope', () => {
  const { host, paged } = setup();
  render(<ViewPage scopeKey="" definitionId="orders" host={host} />);
  expect(screen.getByRole('alert').textContent).toContain('scopeKey');
  expect(host.definition!.load).not.toHaveBeenCalled();
  expect(paged).not.toHaveBeenCalled();
});
it('keeps same-scope drafts across host reference changes and resets on an explicit scope change', async () => {
  const { host, paged } = setup();
  const page = render(
    <ViewPage
      scopeKey="user-one"
      definitionId="orders"
      host={Object.freeze(host)}
    />,
  );
  await screen.findByRole('cell', { name: '42' });
  fireEvent.change(screen.getByRole('textbox', { name: '金额值' }), {
    target: { value: '500' },
  });
  const resolveSource = vi.fn(host.resolveSource);
  const nextHost = { ...host, resolveSource };
  page.rerender(
    <ViewPage scopeKey="user-one" definitionId="orders" host={nextHost} />,
  );
  await waitFor(() =>
    expect(
      (screen.getByRole('textbox', { name: '金额值' }) as HTMLInputElement)
        .value,
    ).toBe('500'),
  );
  expect(paged).toHaveBeenCalledTimes(1);
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  await waitFor(() => expect(resolveSource).toHaveBeenCalledOnce());
  page.rerender(
    <ViewPage
      scopeKey="user-one"
      definitionId="orders"
      host={{
        ...nextHost,
        permission: {
          getInstance: () => ({
            save: false,
            saveAsPersonal: true,
            saveAsShared: false,
          }),
        },
      }}
    />,
  );
  await waitFor(() =>
    expect(screen.queryByRole('button', { name: '保存' })).toBeNull(),
  );
  expect(screen.getByRole('button', { name: '另存为' })).toBeTruthy();
  page.rerender(
    <ViewPage scopeKey="user-two" definitionId="orders" host={nextHost} />,
  );
  await waitFor(() =>
    expect(
      (screen.getByRole('textbox', { name: '金额值' }) as HTMLInputElement)
        .value,
    ).toBe('10'),
  );
  await waitFor(() => expect(paged).toHaveBeenCalledTimes(3));
});
it('owns a working engine across StrictMode replay and stops on unmount', async () => {
  const { host } = setup();
  const view = render(
    <StrictMode>
      <ViewPage scopeKey="test-user" definitionId="orders" host={host} />
    </StrictMode>,
  );
  expect(await screen.findByRole('cell', { name: '42' })).toBeTruthy();
  view.unmount();
  expect(host.definition!.load).toHaveBeenCalledTimes(2);
});
it('shows invalid local JSON as a load error instead of crashing the page', async () => {
  const { host } = setup();
  const malformed = {
    ...definition,
    fields: [{ ...definition.fields[0], extra: NaN }],
  };
  render(
    <ViewPage
      scopeKey="test-user"
      definitionId="orders"
      definition={malformed}
      host={host}
    />,
  );
  expect((await screen.findByRole('alert')).textContent).toContain('JSON');
  expect(host.definition!.load).not.toHaveBeenCalled();
});
