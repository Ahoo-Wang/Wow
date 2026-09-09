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

import { afterEach, beforeEach, expect, it } from 'vitest';
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { filter } from '@ahoo-wang/fetcher-wow';
import { LocalStorageViewHost } from '../src/record/LocalStorageViewHost.js';
import { ViewPage } from '../src/record/ViewPage.js';
import { compileBuiltinFilter } from '../src/filter/filterCore.js';
import type { FilterEditorProps, ViewExtensions } from '../src/react.js';
import { definition, instance, setup } from './fixtures/viewPage.js';

import { storageLock } from './fixtures/storageLock.js';

beforeEach(() => localStorage.clear());
afterEach(cleanup);
function fixture() {
  const { host: source, paged } = setup();
  const saved = structuredClone(instance);
  saved.config.filters.root.component = { name: 'amount-picker' };
  saved.config.filters.root.props = {
    selectedId: 10,
    displayLabel: '人工标签',
  };
  const options = {
    scopeKey: 'contract',
    storage: localStorage,
    serviceKey: 'test-service',
    lock: storageLock,
    definition,
    instances: { instances: [saved], defaultInstanceId: saved.id },
    resolveSource: source.resolveSource,
  };
  return { options, paged };
}
function Editor({ props, onChange }: FilterEditorProps) {
  return (
    <input
      aria-label="自定义标签"
      value={String(props.displayLabel ?? '')}
      onChange={event =>
        onChange({ ...props, displayLabel: event.target.value })
      }
    />
  );
}
const extensions: ViewExtensions = {
  filters: {
    'amount-picker': {
      component: Editor,
      modes: ['simple', 'advanced'],
      compile: (props, context) =>
        compileBuiltinFilter({ value: props.selectedId }, context),
    },
  },
};

it('reconstructs custom components from server JSON with a new host, engine and runtime registry', async () => {
  const { options, paged } = fixture();
  const host = new LocalStorageViewHost(options);
  const mounted = render(
    <ViewPage
      scopeKey="contract"
      definitionId={definition.id}
      host={host}
      extensions={extensions}
    />,
  );
  await screen.findByRole('cell', { name: '42' });
  fireEvent.change(screen.getByRole('textbox', { name: '自定义标签' }), {
    target: { value: '无法从查询表达式恢复的标签' },
  });
  fireEvent.click(screen.getByRole('button', { name: '保存', exact: true }));
  await waitFor(() =>
    expect(
      JSON.parse(localStorage.getItem(host.storageKey)!).instances[0].config
        .filters.root.props.displayLabel,
    ).toBe('无法从查询表达式恢复的标签'),
  );
  const payload = localStorage.getItem(host.storageKey)!;
  expect(
    JSON.parse(payload).instances.find(
      (item: { id: string }) => item.id === 'mine',
    ).config,
  ).not.toHaveProperty('filter');
  expect(paged).toHaveBeenCalledTimes(1);
  mounted.unmount();
  // A different component implementation resolves the same persisted reference; the service JSON is unchanged.
  function Replacement(props: FilterEditorProps) {
    return (
      <section aria-label="替换后的扩展">
        <Editor {...props} />
      </section>
    );
  }
  render(
    <ViewPage
      scopeKey="contract"
      definitionId={definition.id}
      host={new LocalStorageViewHost(options)}
      extensions={{
        filters: {
          'amount-picker': {
            ...extensions.filters!['amount-picker'],
            component: Replacement,
          },
        },
      }}
    />,
  );
  await screen.findByRole('cell', { name: '42' });
  expect(screen.getByRole('region', { name: '替换后的扩展' })).toBeTruthy();
  expect(
    (screen.getByRole('textbox', { name: '自定义标签' }) as HTMLInputElement)
      .value,
  ).toBe('无法从查询表达式恢复的标签');
  expect(paged.mock.lastCall?.[0].filter).toEqual(filter.gte('amount', 10));
  expect(localStorage.getItem(host.storageKey)).toBe(payload);
});

it('blocks a missing runtime extension without deleting service configuration, then recovers when registered', async () => {
  const { options, paged } = fixture();
  const host = new LocalStorageViewHost(options);
  await host.instance!.save(await host.instance!.load(instance.id));
  const payload = localStorage.getItem(host.storageKey);
  const mounted = render(
    <ViewPage scopeKey="contract" definitionId={definition.id} host={host} />,
  );
  await screen.findAllByText(/未注册.*amount-picker/);
  expect(paged).not.toHaveBeenCalled();
  expect(localStorage.getItem(host.storageKey)).toBe(payload);
  mounted.unmount();
  render(
    <ViewPage
      scopeKey="contract"
      definitionId={definition.id}
      host={new LocalStorageViewHost(options)}
      extensions={extensions}
    />,
  );
  await screen.findByRole('cell', { name: '42' });
  expect(paged).toHaveBeenCalledTimes(1);
  expect(localStorage.getItem(host.storageKey)).toBe(payload);
});

it('surfaces a real host revision conflict, preserves the draft, and saves after explicit reload', async () => {
  const { options } = fixture();
  const host = new LocalStorageViewHost(options);
  render(
    <ViewPage
      scopeKey="contract"
      definitionId={definition.id}
      host={host}
      extensions={extensions}
    />,
  );
  await screen.findByRole('cell', { name: '42' });
  fireEvent.change(screen.getByRole('textbox', { name: '自定义标签' }), {
    target: { value: '保留的本地草稿' },
  });
  const remoteActor = new LocalStorageViewHost(options);
  await act(async () =>
    remoteActor.instance!.rename(
      instance.id,
      '服务器更新',
      (await remoteActor.instance!.load(instance.id)).revision,
    ),
  );
  fireEvent.click(screen.getByRole('button', { name: '保存', exact: true }));
  await screen.findByText('视图已被更新，请重新加载');
  expect(
    (await remoteActor.instance!.load(instance.id)).config.filters.root.props
      .displayLabel,
  ).toBe('人工标签');
  expect(
    (screen.getByRole('textbox', { name: '自定义标签' }) as HTMLInputElement)
      .value,
  ).toBe('保留的本地草稿');
  fireEvent.click(screen.getByRole('button', { name: '重新加载并保留编辑' }));
  await waitFor(() =>
    expect(screen.queryByText('视图已被更新，请重新加载')).toBeNull(),
  );
  fireEvent.click(screen.getByRole('button', { name: '保存', exact: true }));
  await waitFor(async () =>
    expect(
      (await remoteActor.instance!.load(instance.id)).config.filters.root.props
        .displayLabel,
    ).toBe('保留的本地草稿'),
  );
});
