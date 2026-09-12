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
import { newFilterNode } from '../../src/filter/filterCore.js';

import { FilterOperator as Op } from '@ahoo-wang/fetcher-wow';
import { afterEach, expect, it, vi } from 'vitest';
import {
  createFilterConfiguration,
  validateFilterConfiguration,
} from '../../src/filter/filterCore.js';
import { validateViewInstance } from '../../src/contracts/validation/instanceValidation.js';
import type { ViewDefinition } from '../../src/contracts/viewModel.js';
import type { ViewEngine } from '../../src/engine/ViewEngine.js';
import { definition, instance, setup } from './fixtures.js';

const engines: ViewEngine[] = [];
afterEach(() => engines.splice(0).forEach(engine => engine.dispose()));
const restricted: ViewDefinition = {
  ...definition,
  allowedOperators: [Op.MATCH_ALL, Op.AND, Op.ELEMENT_MATCH, Op.EQ],
  fields: [
    ...definition.fields,
    {
      field: 'state.items',
      label: 'Items',
      type: 'array',
      fields: [{ field: 'qty', label: 'Quantity', type: 'number' }],
    },
  ],
};
const cases = [
  { ...newFilterNode(Op.GT, 'state.amount'), props: { value: 1 } },
  {
    ...newFilterNode(Op.OR),
    operands: [
      { ...newFilterNode(Op.EQ, 'state.amount'), props: { value: 1 } },
      { ...newFilterNode(Op.EQ, 'state.amount'), props: { value: 2 } },
    ],
  },
  {
    ...newFilterNode(Op.AND),
    operands: [
      { ...newFilterNode(Op.EQ, 'state.id'), props: { value: 'a' } },
      { ...newFilterNode(Op.GT, 'state.amount'), props: { value: 1 } },
    ],
  },
  {
    ...newFilterNode(Op.ELEMENT_MATCH, 'state.items'),
    predicate: { ...newFilterNode(Op.GT, 'qty'), props: { value: 1 } },
  },
];
it.each(cases)(
  'retains excluded operators as repairable configuration: $operator',
  async expression => {
    const saved = instance();
    saved.config.filters = createFilterConfiguration(expression);
    expect(() => validateViewInstance(saved, restricted)).toThrow(
      '当前视图不允许操作',
    );
    const { engine, paged } = setup({
      definition: restricted,
      instances: undefined,
      host: {
        instance: {
          list: async () => ({
            instances: [saved],
            defaultInstanceId: saved.id,
          }),
        },
      },
    });
    engines.push(engine);
    await engine.load();
    expect(engine.getSnapshot().status).toBe('ready');
    expect(
      engine.getSnapshot().sessions[saved.id].validation.length,
    ).toBeGreaterThan(0);
    expect(paged).not.toHaveBeenCalled();
  },
);
it('uses the same boundary when selecting or reloading a remote instance', async () => {
  const forbidden = instance('other');
  forbidden.config.filters = createFilterConfiguration({
    ...newFilterNode(Op.GT, 'state.amount'),
    props: { value: 1 },
  });
  const load = vi.fn().mockResolvedValue(forbidden);
  const { engine, paged } = setup({
    definition: restricted,
    host: { instance: { load } },
  });
  engines.push(engine);
  await engine.load();
  await engine.selectInstance('other');
  expect(engine.getSnapshot().sessions.other.validation.length).toBeGreaterThan(
    0,
  );
  await engine.selectInstance('mine');
  engine.setTitle('Keep this title');
  const before = engine.getSnapshot().sessions.mine;
  load.mockResolvedValue({ ...forbidden, id: 'mine' });
  await engine.reloadInstance('mine');
  expect(engine.getSnapshot().sessions.mine.instance).toEqual(before.instance);
  const beforeAdoption = paged.mock.calls.length;
  await engine.useRemoteInstance(
    engine.getSnapshot().sessions.mine.conflict!,
    'mine',
  );
  expect(engine.getSnapshot().sessions.mine.validation.length).toBeGreaterThan(
    0,
  );
  expect(paged).toHaveBeenCalledTimes(beforeAdoption);
});
it('keeps permitted opaque editor props valid without requiring a compiler at the structural boundary', () => {
  const saved = instance();
  saved.config.filters = createFilterConfiguration({
    ...{ ...newFilterNode(Op.EQ, 'state.amount'), props: { value: 1 } },
    component: { name: 'custom' },
    props: { tokens: ['a'] },
  });
  expect(() => validateViewInstance(saved, restricted)).not.toThrow();
  expect(() =>
    validateFilterConfiguration(saved.config.filters, restricted.fields),
  ).not.toThrow();
  expect(() =>
    validateFilterConfiguration(saved.config.filters, restricted.fields, []),
  ).toThrow('当前视图不允许操作');
});
