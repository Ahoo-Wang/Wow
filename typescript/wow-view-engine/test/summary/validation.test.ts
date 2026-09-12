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

import { filter } from '@ahoo-wang/fetcher-wow';
import { expect, it } from 'vitest';
import type { RecordColumn } from '../../src/contracts/viewModel.js';
import { getRecordSummaryFunctions } from '../../src/record/recordPresentation.js';
import { createRecordSummaryQuery } from '../../src/record/recordSummary.js';
import { validateViewDefinition } from '../../src/contracts/validation/definitionValidation.js';
import { validateViewInstance } from '../../src/contracts/validation/instanceValidation.js';
import { definition, metricsFor, setup } from './fixtures.js';

it('rejects duplicate or unsupported selections and counts the total number of metrics', () => {
  const { engine, instance } = setup();
  const validate = (columns: unknown[]) =>
    validateViewInstance(
      {
        ...instance,
        config: {
          ...instance.config,
          presentation: { layout: 'table', table: { columns } },
        },
      },
      definition,
    );
  for (const summary of ['SUM', ['SUM', 'SUM'], ['COUNT'], [null]])
    expect(() =>
      validate([{ id: 'amount', kind: 'field', field: 'amount', summary }]),
    ).toThrow();
  expect(() =>
    validate([{ id: 'amount', kind: 'field', field: 'amount', summary: [] }]),
  ).not.toThrow();
  const all = Array.from({ length: 17 }, (_, index) => ({
    id: `amount${index}`,
    kind: 'field',
    field: 'amount',
    summary: ['SUM', 'AVG', 'MIN', 'MAX'],
  }));
  expect(() => validate(all.slice(0, 16))).not.toThrow();
  expect(() => validate(all)).toThrow('最多配置 64 个汇总指标');
  expect(() =>
    createRecordSummaryQuery(
      filter.matchAll(),
      metricsFor(all as RecordColumn[]),
    ),
  ).toThrow('最多配置 64 个汇总指标');
  engine.dispose();
});

it('offers only numeric summaries and rejects COUNT or nonnumeric field capabilities', () => {
  const { engine, instance } = setup();
  expect(getRecordSummaryFunctions(definition.fields[0])).toEqual([]);
  expect(getRecordSummaryFunctions(definition.fields[1])).toEqual([
    'SUM',
    'AVG',
    'MIN',
    'MAX',
  ]);
  expect(
    getRecordSummaryFunctions({
      field: 'enabled',
      label: '启用',
      type: 'boolean',
    }),
  ).toEqual([]);
  expect(
    getRecordSummaryFunctions({
      ...definition.fields[1],
      summaryFunctions: ['MAX'],
    }),
  ).toEqual(['MAX']);
  expect(() =>
    validateViewDefinition({
      ...definition,
      fields: [
        {
          field: 'id',
          label: '编号',
          type: 'string',
          summaryFunctions: ['SUM'],
        },
      ],
    }),
  ).toThrow();
  expect(() =>
    validateViewInstance(
      {
        ...instance,
        config: {
          ...instance.config,
          presentation: {
            layout: 'table',
            table: {
              columns: [
                { id: 'id', kind: 'field', field: 'id', summary: ['SUM'] },
              ],
            },
          },
        },
      },
      definition,
    ),
  ).toThrow();
  for (const field of ['id', 'amount'])
    expect(() =>
      validateViewInstance(
        {
          ...instance,
          config: {
            ...instance.config,
            presentation: {
              layout: 'table',
              table: {
                columns: [
                  { id: field, kind: 'field', field, summary: ['COUNT'] },
                ],
              },
            },
          },
        },
        definition,
      ),
    ).toThrow();
  expect(() =>
    validateViewInstance(instance, {
      ...definition,
      fields: [
        definition.fields[0],
        { ...definition.fields[1], summaryFunctions: [] },
      ],
    }),
  ).toThrow();
  engine.dispose();
});
