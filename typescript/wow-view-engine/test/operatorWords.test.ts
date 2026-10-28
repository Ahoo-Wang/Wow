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

import { describe, expect, it } from 'vitest';
import {
  ComparisonOperators,
  DateDiffUnits,
  DeletionStates,
  FilterOperators,
  type FieldDefinition,
  type FilterLeaf,
  type FilterOperatorName,
} from '../src/model/index.js';
import {
  BUILTIN_FIELD_KINDS,
  builtinFieldKinds,
  describeFilter,
  describePresence,
  DURATION_COMPARISONS,
  impliedDeletion,
  withFieldKinds,
  type EditorDescriptor,
  type FieldKind,
} from '../src/filter/index.js';
import {
  COMPARISON_SIGN,
  DATE_DIFF_UNIT_TEXT,
  DELETION_TEXT,
  OPERATOR_TEXT,
} from '../src/filter/operatorWords.js';
import { en } from '../src/ui/messages/en.js';

/** The tables' own English words: a wire value spelled like one is a word. */
const ENGLISH = new Set(
  [
    ...Object.values(OPERATOR_TEXT),
    ...Object.values(DELETION_TEXT),
    ...Object.values(DATE_DIFF_UNIT_TEXT).flatMap(unit => [
      unit.one,
      unit.other,
    ]),
  ].flatMap(phrase => phrase.split(' ')),
);

/** Every wire value a condition stores that no English sentence says. */
const WIRE = [
  ...Object.values(FilterOperators),
  ...Object.values(ComparisonOperators),
  ...Object.values(DeletionStates),
  ...Object.values(DateDiffUnits),
].filter(value => !ENGLISH.has(value));

function wireIn(text: string): string[] {
  return text.split(/[\s,()~]+/).filter(token => WIRE.includes(token as never));
}

const others: FieldDefinition[] = [
  { name: 'paid', label: 'Paid at', kind: 'datetime' },
];

function fieldOf(kind: FieldKind): FieldDefinition {
  return {
    name: 'f',
    label: 'Field',
    kind: kind.id,
    options: [
      { value: 'a', label: 'Alpha' },
      { value: 'true', label: 'Yes' },
    ],
    elements: [{ name: 'sku', label: 'SKU', kind: 'string' }],
  };
}

/** The values an editor collects, every shape it may take. */
function samples(editor: EditorDescriptor): unknown[] {
  switch (editor.input) {
    case 'none':
      return [undefined];
    case 'text':
      return editor.multiple ? [['Ann', 'Bo']] : ['Ann'];
    case 'number':
      return editor.range ? [[1, 9]] : editor.multiple ? [[1, 2]] : [7];
    case 'boolean':
      return [true, false];
    case 'deletion':
      return Object.values(DeletionStates);
    case 'select':
      return [['a']];
    case 'remote':
      return [{ items: [{ id: 1, label: 'Ann' }] }];
    case 'date':
    case 'dateRange':
    case 'relativeDate':
      return [
        { type: 'absolute', from: '2026-01-01' },
        { type: 'absolute', from: '2026-01-01', to: '2026-02-01' },
        { type: 'relative', amount: 1, unit: 'day' },
        { type: 'relative', amount: 7, unit: 'day', direction: 'future' },
        { type: 'preset', preset: 'thisMonth' },
      ];
    case 'predicate':
      return [
        {
          op: FilterOperators.OR,
          children: [
            { field: 'f.sku', operator: FilterOperators.EQ, value: 'A' },
            { field: 'f.sku', operator: FilterOperators.NE, value: 'B' },
          ],
        },
      ];
    case 'duration':
      return DURATION_COMPARISONS.flatMap(comparison =>
        Object.values(DateDiffUnits).flatMap(unit =>
          [1, 48].map(value => ({ from: 'paid', comparison, value, unit })),
        ),
      );
  }
}

describe("a summary's English says no wire value", () => {
  it('in any kind, for any operator it offers, over any value', () => {
    const leaked: string[] = [];
    for (const kind of BUILTIN_FIELD_KINDS) {
      const field = fieldOf(kind);
      for (const operator of kind.operators)
        for (const value of samples(kind.editor(operator, field))) {
          const leaf: FilterLeaf = {
            field: field.name,
            operator,
            value: value as FilterLeaf['value'],
          };
          const { text } = kind.describe({
            leaf,
            field,
            fields: [field, ...others],
            kinds: builtinFieldKinds,
          });
          if (wireIn(text).length > 0)
            leaked.push(`${kind.id} ${operator}: ${text}`);
        }
    }
    expect(leaked).toEqual([]);
  });

  it('for a condition its kind could not read', () => {
    const throwing = withFieldKinds(builtinFieldKinds, [
      {
        ...builtinFieldKinds.get('string')!,
        id: 'throwing',
        describe() {
          throw new Error('boom');
        },
      },
    ]);
    const field = { name: 'f', label: 'Field', kind: 'throwing' };
    const operators = Object.values(FilterOperators).filter(
      operator =>
        operator !== FilterOperators.AND &&
        operator !== FilterOperators.OR &&
        operator !== FilterOperators.NOR,
    );
    const texts = operators.flatMap(operator =>
      describeFilter(
        [field],
        {
          op: FilterOperators.AND,
          children: [{ field: 'f', operator, value: 'x' }],
        },
        throwing,
      ).map(item => item.text),
    );
    expect(texts.flatMap(wireIn)).toEqual([]);
    expect(texts).toContain(`Field ${OPERATOR_TEXT[FilterOperators.GTE]}`);
  });

  it('for the deletion reading nobody wrote', () => {
    const [item] = impliedDeletion(
      [{ name: '@deleted', label: 'Records', kind: 'deletion' }],
      [null],
      builtinFieldKinds,
    );
    expect(item.text).toBe('Records Not deleted');
  });

  it('for a presence question', () => {
    const said = Object.values(FilterOperators).flatMap(operator => {
      const words = describePresence(operator);
      return words === null ? [] : [words];
    });
    expect(said).toEqual([
      'is empty',
      'is not empty',
      'exists',
      'does not exist',
    ]);
  });
});

describe('the English catalogue reads the kernel’s words', () => {
  const catalogue: Readonly<Record<string, string>> = en;

  it('for every operator', () => {
    for (const operator of Object.keys(OPERATOR_TEXT))
      expect(catalogue[`label.operator.${operator}`]).toBe(
        OPERATOR_TEXT[operator as FilterOperatorName],
      );
  });

  it('for every deletion reading', () => {
    expect({
      [DeletionStates.ACTIVE]: catalogue['label.deletion.active'],
      [DeletionStates.DELETED]: catalogue['label.deletion.deleted'],
      [DeletionStates.ALL]: catalogue['label.deletion.all'],
    }).toEqual(DELETION_TEXT);
  });

  it('for every unit of a time between, capitalised for a select', () => {
    for (const [unit, words] of Object.entries(DATE_DIFF_UNIT_TEXT))
      expect(catalogue[`label.date-diff-unit.${unit}`]?.toLowerCase()).toBe(
        words.other,
      );
  });

  it('signs every comparison', () => {
    expect(Object.keys(COMPARISON_SIGN).sort()).toEqual(
      Object.values(ComparisonOperators).sort(),
    );
  });
});
