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
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, expect, it } from 'vitest';
import { ViewEngine } from '../src/engine/ViewEngine.js';
import { ViewPageContent } from '../src/view/ViewPage.js';
import { definition, instance, setup } from './fixtures/viewPage.js';
afterEach(cleanup);

it('shows both versions and rejects a stale overwrite dialog before accepting a fresh review', async () => {
  const { host } = setup();
  const remote = {
    ...structuredClone(instance),
    title: '远端订单',
    revision: 'r2',
  };
  remote.config.pagination.size = 50;
  host.instance!.load = async () => remote;
  const engine = new ViewEngine({
    definitionId: definition.id,
    definition,
    instances: {
      instances: [{ ...instance, revision: 'r1' }],
      defaultInstanceId: 'mine',
    },
    host,
  });
  try {
    await engine.load();
    engine.setTitle('本地订单');
    await engine.reloadInstance();
    render(<ViewPageContent engine={engine} />);
    expect(screen.getByRole('alert', { name: '视图版本冲突' })).toBeTruthy();
    expect(
      screen.getByRole('button', { name: '保存', exact: true }),
    ).toHaveProperty('disabled', true);
    fireEvent.click(
      screen.getByRole('button', { name: '覆盖远端版本', exact: true }),
    );
    expect(
      await screen.findByRole('dialog', { name: '确认覆盖远端版本' }),
    ).toBeTruthy();
    expect(
      screen.getByRole('region', { name: '远端版本' }).textContent,
    ).toContain('每页 50 条');
    act(() => engine.setTitle('稍后编辑'));
    fireEvent.click(
      screen.getByRole('button', { name: '确认覆盖远端版本', exact: true }),
    );
    expect(
      await screen.findByText('远端版本或本地编辑已变化，请重新确认冲突'),
    ).toBeTruthy();
    expect(host.instance!.save).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '取消', exact: true }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    fireEvent.click(
      screen.getByRole('button', { name: '覆盖远端版本', exact: true }),
    );
    fireEvent.click(
      await screen.findByRole('button', {
        name: '确认覆盖远端版本',
        exact: true,
      }),
    );
    await waitFor(() =>
      expect(engine.getSnapshot().sessions.mine.conflict).toBeUndefined(),
    );
    expect(host.instance!.save).toHaveBeenCalledWith(
      expect.objectContaining({
        revision: 'r2',
        title: '稍后编辑',
        config: expect.objectContaining({
          pagination: { mode: 'paged', size: 10 },
        }),
      }),
    );
  } finally {
    engine.dispose();
  }
});

it('allows read-only users to explicitly adopt the latest version without a write', async () => {
  const { host } = setup();
  host.instance!.load = async () => ({
    ...structuredClone(instance),
    title: '远端订单',
    revision: 'r2',
  });
  host.permission = {
    getInstance: () => ({
      save: false,
      saveAsPersonal: false,
      saveAsShared: false,
    }),
  };
  const engine = new ViewEngine({
    definitionId: definition.id,
    definition,
    instances: { instances: [instance], defaultInstanceId: 'mine' },
    host,
  });
  try {
    await engine.load();
    engine.setTitle('本地订单');
    await engine.reloadInstance();
    render(<ViewPageContent engine={engine} />);
    expect(screen.queryByRole('button', { name: '覆盖远端版本' })).toBeNull();
    fireEvent.click(
      screen.getByRole('button', { name: '使用最新版本', exact: true }),
    );
    fireEvent.click(
      await screen.findByRole('button', {
        name: '确认使用最新版本',
        exact: true,
      }),
    );
    await waitFor(() =>
      expect(engine.getSnapshot().sessions.mine.conflict).toBeUndefined(),
    );
    expect(engine.getSnapshot().sessions.mine.instance.title).toBe('远端订单');
    expect(host.instance!.save).not.toHaveBeenCalled();
  } finally {
    engine.dispose();
  }
});
