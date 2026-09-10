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

import { afterEach, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { RecordCardSettings } from '../src/record/RecordCardSettings.js';
import { definition } from './fixtures/recordTable.js';
afterEach(cleanup);
it('retains custom actions and the draft after submission failure', async () => {
  const card = {
    title: { id: 'title', field: 'name' },
    fields: [],
    actions: { renderer: { name: 'custom' } },
  };
  const onChange = vi.fn().mockImplementationOnce(() => {
    throw new Error('保存设置失败');
  });
  render(
    <RecordCardSettings
      definition={definition}
      card={card}
      onChange={onChange}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: '卡片设置' }));
  fireEvent.click(await screen.findByRole('checkbox', { name: '显示操作区' }));
  fireEvent.click(screen.getByRole('button', { name: '应用设置' }));
  expect(await screen.findByRole('alert')).toHaveProperty(
    'textContent',
    '保存设置失败',
  );
  expect(screen.getByRole('dialog')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: '应用设置' }));
  expect(onChange.mock.calls[1][0].actions).toEqual({
    visible: false,
    renderer: { name: 'custom' },
  });
});

it('shows an explicit no-cover value and explains exhausted field choices', async () => {
  const card = {
    title: { id: 'title', field: 'name' },
    fields: definition.fields.map(field => ({
      id: field.field,
      field: field.field,
    })),
  };
  render(
    <RecordCardSettings
      definition={definition}
      card={card}
      onChange={vi.fn()}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: '卡片设置' }));
  expect(
    (await screen.findByRole('combobox', { name: '封面字段' })).textContent,
  ).toContain('无封面');
  expect(screen.getByRole('combobox', { name: '添加摘要字段' })).toHaveProperty(
    'disabled',
    true,
  );
  expect(screen.getByText('已添加全部字段')).toBeTruthy();
  expect(screen.queryByText(/可保存到视图/)).toBeNull();
});

it('focuses the re-enabled add control after removing from an exhausted list', async () => {
  const card = {
    title: { id: 'title', field: 'name' },
    fields: definition.fields.map(field => ({
      id: field.field,
      field: field.field,
    })),
  };
  render(
    <RecordCardSettings
      definition={definition}
      card={card}
      onChange={vi.fn()}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: '卡片设置' }));
  const remove = await screen.findByRole('button', { name: '移除摘要 1' });
  remove.focus();
  fireEvent.click(remove);
  expect(document.activeElement).toBe(
    screen.getByRole('combobox', { name: '添加摘要字段' }),
  );
});

it('reorders draft fields with the shared keyboard handle and applies once', async () => {
  const onChange = vi.fn();
  const fields = [
    { id: 'first', field: 'name' },
    { id: 'second', field: 'amount' },
  ];
  render(
    <RecordCardSettings
      definition={definition}
      card={{ title: { id: 'title', field: 'name' }, fields }}
      onChange={onChange}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: '卡片设置' }));
  const handle = await screen.findByRole('button', {
    name: '拖动调整摘要 1 顺序',
  });
  expect((handle as HTMLButtonElement).draggable).toBe(true);
  fireEvent.keyDown(handle, { key: 'ArrowDown' });
  expect(onChange).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: '应用设置' }));
  expect(onChange.mock.calls[0][0].fields).toEqual([fields[1], fields[0]]);
});

it('drops a summary field at the indicated boundary', async () => {
  const onChange = vi.fn();
  const fields = [
    { id: 'first', field: 'name' },
    { id: 'second', field: 'amount' },
  ];
  render(
    <RecordCardSettings
      definition={definition}
      card={{ title: { id: 'title', field: 'name' }, fields }}
      onChange={onChange}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: '卡片设置' }));
  const handle = await screen.findByRole('button', {
    name: '拖动调整摘要 1 顺序',
  });
  const list = screen.getByRole('list', { name: '摘要字段' });
  Array.from(list.children).forEach((row, index) => {
    row.getBoundingClientRect = () =>
      ({ top: index * 40, bottom: (index + 1) * 40, height: 40 }) as DOMRect;
  });
  const dataTransfer = { setData: vi.fn(), effectAllowed: '', dropEffect: '' };
  fireEvent.dragStart(handle, { dataTransfer });
  fireEvent.dragOver(list, { dataTransfer, clientY: 79 });
  expect(list.querySelector('[data-slot="card-drop-indicator"]')).toBeTruthy();
  fireEvent.drop(list, { dataTransfer, clientY: 79 });
  fireEvent.click(screen.getByRole('button', { name: '应用设置' }));
  expect(onChange.mock.calls[0][0].fields).toEqual([fields[1], fields[0]]);
});

async function choose(label: string, name: string) {
  fireEvent.click(screen.getByRole('combobox', { name: label }));
  const option = await screen.findByRole('option', { name, exact: true });
  fireEvent.pointerDown(option, { pointerType: 'mouse' });
  fireEvent.click(option);
}
it('edits title, cover and summary fields as a draft and cancels without writing', async () => {
  const onChange = vi.fn();
  const card = { title: { id: 'title', field: 'name' }, fields: [] };
  render(
    <RecordCardSettings
      definition={definition}
      card={card}
      onChange={onChange}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: '卡片设置' }));
  await screen.findByRole('combobox', { name: '标题字段' });
  await choose('标题字段', '金额');
  await choose('封面字段', '名称');
  await choose('添加摘要字段', '金额');
  expect(onChange).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: '取消' }));
  expect(onChange).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: '卡片设置' }));
  expect(
    (await screen.findByRole('combobox', { name: '标题字段' })).textContent,
  ).toContain('名称');
  expect(
    screen.getByRole('combobox', { name: '封面字段' }).textContent,
  ).toContain('无封面');
  await choose('标题字段', '金额');
  await choose('封面字段', '名称');
  await choose('添加摘要字段', '金额');
  fireEvent.click(screen.getByRole('button', { name: '应用设置' }));
  const applied = onChange.mock.lastCall?.[0];
  expect(applied.title).toEqual({ id: 'title', field: 'amount' });
  expect(applied.cover).toEqual({ field: 'name' });
  expect(applied.fields).toEqual([{ id: expect.any(String), field: 'amount' }]);
  expect(card).toEqual({ title: { id: 'title', field: 'name' }, fields: [] });
  fireEvent.click(screen.getByRole('button', { name: '卡片设置' }));
  await screen.findByRole('combobox', { name: '封面字段' });
  await choose('封面字段', '名称');
  await choose('封面字段', '无封面');
  fireEvent.click(screen.getByRole('button', { name: '应用设置' }));
  expect(onChange.mock.lastCall?.[0]).not.toHaveProperty('cover');
});

it('locks every editable control when disabled after opening and preserves the draft', async () => {
  const card = {
    title: { id: 'title', field: 'name' },
    fields: [{ id: 'amount', field: 'amount' }],
    actions: { renderer: { name: 'custom' } },
  };
  const onChange = vi.fn();
  const view = render(
    <RecordCardSettings
      definition={definition}
      card={card}
      onChange={onChange}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: '卡片设置' }));
  await screen.findByRole('combobox', { name: '标题字段' });
  view.rerender(
    <RecordCardSettings
      definition={definition}
      card={card}
      onChange={onChange}
      disabled
    />,
  );
  for (const name of ['标题字段', '封面字段', '添加摘要字段'])
    expect(screen.getByRole('combobox', { name })).toHaveProperty(
      'disabled',
      true,
    );
  expect(screen.getByRole('button', { name: '移除摘要 1' })).toHaveProperty(
    'disabled',
    true,
  );
  expect(
    screen
      .getByRole('checkbox', { name: '显示操作区' })
      .getAttribute('aria-disabled'),
  ).toBe('true');
  fireEvent.click(screen.getByRole('button', { name: '移除摘要 1' }));
  fireEvent.click(screen.getByRole('checkbox', { name: '显示操作区' }));
  expect(onChange).not.toHaveBeenCalled();
  view.rerender(
    <RecordCardSettings
      definition={definition}
      card={card}
      onChange={onChange}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: '应用设置' }));
  expect(onChange).toHaveBeenCalledWith(card);
});
