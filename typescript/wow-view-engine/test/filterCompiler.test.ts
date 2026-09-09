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

import { filter, FilterOperator as Op } from '@ahoo-wang/fetcher-wow';
import { expect, it } from 'vitest';
import { compileFilterConfiguration } from '../src/filter/filterCore.js';
import type {
  FilterCompilerRegistry,
  FilterComponentConfig,
} from '../src/filter/filterModel.js';
import { sameFilterQuery } from '../src/filter/filterTree.js';
import { fields } from './fixtures/filterCore.js';

const draft: FilterComponentConfig = {
  id: 'custom',
  operator: Op.EQ,
  field: 'amount',
  component: { name: 'range', options: { inclusive: true } },
  props: { minimum: 0, maximum: 10, displayLabel: '零至十', unset: undefined },
};
const compilers: FilterCompilerRegistry = {
  range: {
    compile(props, context) {
      expect(context.options).toEqual({ inclusive: true });
      return filter.and([
        filter.gte(context.field!.field, props.minimum as number),
        filter.lte(context.field!.field, props.maximum as number),
      ]);
    },
  },
};

it('compiles opaque component properties without mounting or replacing their configuration', () => {
  const before = structuredClone(draft);
  expect(
    compileFilterConfiguration(
      { mode: 'advanced', root: draft },
      fields,
      undefined,
      compilers,
    ),
  ).toEqual({
    expression: filter.and([filter.gte('amount', 0), filter.lte('amount', 10)]),
    errors: [],
  });
  expect(draft).toEqual(before);
});

it('resolves an explicit component before changed field and operator defaults', () => {
  expect(
    compileFilterConfiguration(
      { mode: 'advanced', root: draft },
      fields.map(field => ({ ...field, editor: { name: 'unknown' } })),
      undefined,
      compilers,
    ).errors,
  ).toEqual([]);
});

it('fails closed for missing compilers, malformed output and cross-field output', () => {
  expect(
    compileFilterConfiguration({ mode: 'advanced', root: draft }, fields)
      .expression,
  ).toBeUndefined();
  for (const expression of [
    filter.eq('name', 'wrong'),
    filter.matchAll(),
    { op: Op.EQ, field: 'amount' },
    filter.elementMatch('items', filter.eq('quantity', 1)),
  ]) {
    const result = compileFilterConfiguration(
      { mode: 'advanced', root: draft },
      fields,
      undefined,
      {
        range: { compile: () => expression as ReturnType<typeof filter.eq> },
      },
    );
    expect(result.errors.length).toBeGreaterThan(0);
    expect(result.expression).toBeUndefined();
  }
});

it('validates compiler output capabilities and allowed operators', () => {
  for (const definitions of [
    fields,
    fields.map(field => ({ ...field, operators: [Op.EQ] })),
  ]) {
    expect(
      compileFilterConfiguration(
        { mode: 'advanced', root: draft },
        definitions,
        [Op.EQ],
        compilers,
      ).errors.length,
    ).toBeGreaterThan(0);
  }
});

it('keeps unset custom props distinct from empty strings, false and zero', () => {
  const custom: FilterCompilerRegistry = {
    range: {
      compile: props =>
        props.value === undefined
          ? undefined
          : filter.eq('amount', props.value as number),
    },
  };
  expect(
    compileFilterConfiguration(
      { mode: 'advanced', root: { ...draft, props: { label: 'empty' } } },
      fields,
      undefined,
      custom,
    ),
  ).toEqual({ expression: filter.matchAll(), errors: [] });
  expect(
    compileFilterConfiguration(
      { mode: 'advanced', root: { ...draft, props: { value: 0 } } },
      fields,
      undefined,
      custom,
    ).expression,
  ).toEqual(filter.eq('amount', 0));
});

it('compares query semantics across redundant singleton AND and OR wrappers only', () => {
  const value = filter.gte('amount', 10);
  expect(sameFilterQuery(value, filter.and([filter.or([value])]))).toBe(true);
  expect(sameFilterQuery(value, filter.nor([value]))).toBe(false);
  expect(sameFilterQuery(null, filter.matchAll())).toBe(false);
});
