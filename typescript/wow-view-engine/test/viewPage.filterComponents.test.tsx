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

import { filter, FilterOperator } from '@ahoo-wang/fetcher-wow';
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import type {
  FilterEditorProps,
  FilterRegistration,
} from '../src/filter/filterReactTypes.js';
import { ViewPage } from '../src/record/ViewPage.js';
import type { ViewInstance } from '../src/record/recordModel.js';
import { instance, setup } from './fixtures/viewPage.js';

afterEach(cleanup);

function CustomAmount({ props, onChange, disabled }: FilterEditorProps) {
  return (
    <>
      <label>
        {String(props.caption)}
        <input
          aria-label="自定义金额"
          disabled={disabled}
          value={typeof props.threshold === 'number' ? props.threshold : ''}
          onChange={event =>
            onChange({
              ...props,
              threshold:
                event.target.value === ''
                  ? undefined
                  : Number(event.target.value),
            })
          }
        />
      </label>
      <button
        disabled={disabled}
        onClick={() => onChange({ ...props, caption: '重点预算' })}
      >
        重命名筛选提示
      </button>
    </>
  );
}

const customAmount: FilterRegistration = {
  component: CustomAmount,
  modes: ['simple', 'advanced'],
  compile: (props, context) =>
    typeof props.threshold === 'number'
      ? filter.gte(context.field!.field, props.threshold)
      : undefined,
  clear: props => ({ ...props, threshold: undefined }),
};

function customInstance(): ViewInstance {
  return {
    ...structuredClone(instance),
    config: {
      ...structuredClone(instance.config),
      filters: {
        mode: 'simple',
        root: {
          id: 'custom-amount',
          component: { name: 'custom', options: { currency: 'CNY' } },
          operator: FilterOperator.GTE,
          field: 'amount',
          props: {
            threshold: 10,
            caption: '预算阈值',
            presentation: { emphasis: false },
          },
        },
      },
    },
  };
}

it('renders and clears opaque component props without losing them through JSON save and reload', async () => {
  const { host, paged } = setup();
  let saved = customInstance();
  host.instance!.list = vi.fn(async () => ({
    instances: [saved],
    defaultInstanceId: saved.id,
  }));
  host.instance!.save = vi.fn(async submitted => {
    saved = JSON.parse(JSON.stringify(submitted)) as ViewInstance;
    return saved;
  });
  const view = render(
    <ViewPage
      scopeKey="custom-one"
      definitionId="orders"
      host={host}
      extensions={{ filters: { custom: customAmount } }}
    />,
  );
  await screen.findByRole('cell', { name: '42' });
  const input = screen.getByRole('textbox', {
    name: '自定义金额',
  }) as HTMLInputElement;
  expect(input.value).toBe('10');
  expect(screen.getByText('预算阈值')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: '重命名筛选提示' }));
  expect(screen.getByText('重点预算')).toBeTruthy();
  expect(
    (
      screen.getByRole('button', {
        name: '保存',
        exact: true,
      }) as HTMLButtonElement
    ).disabled,
  ).toBe(false);
  expect(paged).toHaveBeenCalledTimes(1);
  const summary = screen.getByRole('region', { name: '已应用筛选' });
  fireEvent.click(
    within(summary).getByRole('button', { name: /金额 大于等于 10/ }),
  );
  await waitFor(() => expect(paged).toHaveBeenCalledTimes(2));
  expect(paged.mock.lastCall?.[0].filter).toEqual(filter.matchAll());
  expect(screen.getByRole('textbox', { name: '自定义金额' })).toBe(input);
  expect(input.value).toBe('');
  expect(document.activeElement).toBe(summary);
  fireEvent.click(screen.getByRole('button', { name: '保存', exact: true }));
  await waitFor(() => expect(host.instance!.save).toHaveBeenCalledTimes(1));
  expect(saved.config.filters.root).toEqual({
    ...customInstance().config.filters.root,
    props: { caption: '重点预算', presentation: { emphasis: false } },
  });
  view.rerender(
    <ViewPage
      scopeKey="custom-two"
      definitionId="orders"
      host={host}
      extensions={{ filters: { custom: customAmount } }}
    />,
  );
  await screen.findByRole('cell', { name: '42' });
  expect(
    (screen.getByRole('textbox', { name: '自定义金额' }) as HTMLInputElement)
      .value,
  ).toBe('');
  expect(screen.getByText('重点预算')).toBeTruthy();
  expect(paged).toHaveBeenCalledTimes(3);
});

it.each([
  undefined,
  () => {
    throw new TypeError('清除配置不可用');
  },
])(
  'keeps applied tags readable when custom clearing is unavailable: %s',
  async clear => {
    const { host, paged } = setup();
    render(
      <ViewPage
        scopeKey="custom-one"
        definitionId="orders"
        host={host}
        instances={{
          instances: [customInstance()],
          defaultInstanceId: instance.id,
        }}
        extensions={{ filters: { custom: { ...customAmount, clear } } }}
      />,
    );
    await screen.findByRole('cell', { name: '42' });
    const summary = screen.getByRole('region', { name: '已应用筛选' });
    expect(summary.textContent).toContain('金额 大于等于 10');
    expect(within(summary).queryByRole('button')).toBeNull();
    expect(paged).toHaveBeenCalledTimes(1);
  },
);

it('keeps the initial compiler and filter renderer together until the scope changes', async () => {
  const { host, paged } = setup();
  const local = {
    instances: [customInstance()],
    defaultInstanceId: instance.id,
  };
  const view = render(
    <ViewPage
      scopeKey="custom-one"
      definitionId="orders"
      host={host}
      instances={local}
      extensions={{ filters: { custom: customAmount } }}
    />,
  );
  await screen.findByRole('cell', { name: '42' });
  const compile = vi.fn(() => filter.lte('amount', 50));
  view.rerender(
    <ViewPage
      scopeKey="custom-one"
      definitionId="orders"
      host={host}
      instances={local}
      extensions={{
        filters: {
          custom: {
            ...customAmount,
            component: () => <span>新的编译组件</span>,
            compile,
          },
        },
      }}
    />,
  );
  const input = screen.getByRole('textbox', { name: '自定义金额' });
  fireEvent.change(input, { target: { value: '20' } });
  fireEvent.keyDown(input, { key: 'Enter' });
  await waitFor(() => expect(paged).toHaveBeenCalledTimes(2));
  expect(paged.mock.lastCall?.[0].filter).toEqual(filter.gte('amount', 20));
  expect(compile).not.toHaveBeenCalled();
  view.rerender(
    <ViewPage
      scopeKey="custom-two"
      definitionId="orders"
      host={host}
      instances={local}
      extensions={{
        filters: {
          custom: {
            ...customAmount,
            component: () => <span>新的编译组件</span>,
            compile,
          },
        },
      }}
    />,
  );
  await screen.findByText('新的编译组件');
  await waitFor(() => expect(paged).toHaveBeenCalledTimes(3));
  expect(paged.mock.lastCall?.[0].filter).toEqual(filter.lte('amount', 50));
});

it('does not query from core-only compiler options accidentally spread into a React page', async () => {
  const { host, paged } = setup();
  const compile = vi.fn(customAmount.compile);
  const options = {
    definitionId: 'orders',
    host,
    instances: {
      instances: [customInstance()],
      defaultInstanceId: instance.id,
    },
    filterCompilers: { custom: { compile } },
  };
  render(<ViewPage scopeKey="component-registry" {...options} />);
  await screen.findByText('筛选组件配置无法编译，请先修正筛选');
  expect(paged).not.toHaveBeenCalled();
  expect(compile).not.toHaveBeenCalled();
});
