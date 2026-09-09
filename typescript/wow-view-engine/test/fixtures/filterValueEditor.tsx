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

import { fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import {
  FilterValueEditor,
  type FilterValueEditorProps,
} from '../../src/filter/FilterValueEditor';
import type {
  FilterComponentConfig,
  FilterFieldDefinition,
} from '../../src/filter/filterModel';

export const fields: FilterFieldDefinition[] = [
  { field: 'name', label: '名称', type: 'string' },
  { field: 'amount', label: '金额', type: 'number' },
  { field: 'enabled', label: '启用', type: 'boolean' },
  {
    field: 'createdAt',
    label: '创建时间',
    type: 'datetime',
  },
  { field: 'birthday', label: '生日', type: 'date' },
  {
    field: 'status',
    label: '状态',
    options: [
      { value: 0, label: '零' },
      { value: '0', label: '字符串零' },
      { value: false, label: '否' },
      { value: '', label: '空标签' },
    ],
  },
];
export function mount(
  initial: FilterComponentConfig,
  field = fields.find(item => item.field === initial.field),
  options: Pick<FilterValueEditorProps, 'showTime' | 'timeZone'> = {},
) {
  const changes: FilterComponentConfig[] = [];
  let current = initial;
  function Example() {
    const [node, setNode] = useState(initial);
    return (
      <FilterValueEditor
        node={node}
        field={field}
        fields={fields}
        {...options}
        onChange={next => {
          current = next;
          changes.push(next);
          setNode(next);
        }}
      />
    );
  }
  const result = render(<Example />);
  return { ...result, changes, current: () => current };
}
export function change(label: string, value: string) {
  fireEvent.change(screen.getByRole('textbox', { name: label, exact: true }), {
    target: { value },
  });
}
export async function select(label: string, option: string) {
  fireEvent.click(screen.getByRole('combobox', { name: label, exact: true }));
  const item = await screen.findByRole('option', { name: option, exact: true });
  fireEvent.pointerDown(item, { pointerType: 'mouse' });
  fireEvent.click(item);
}
