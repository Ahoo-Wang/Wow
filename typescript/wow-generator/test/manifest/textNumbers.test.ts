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

/**
 * How the exact numbers of a Manifest's schemas are derived, for the shapes
 * a generated client's probe (test/probes/exactNumbers.test.ts) does not
 * reach: what a reader needs, not how it is held.
 */

import { describe, expect, it } from 'vitest';
import { GeneratorError } from '../../src/api/errors';
import { TextNumbers } from '../../src/manifest/textNumbers';

const ref = (key: string) => ({ $ref: `#/$defs/${key}` });
const long = { type: 'integer', format: 'int64' };
const decimal = { type: 'number', format: 'decimal' };
const double = { type: 'number', format: 'double' };
const object = (properties: Record<string, unknown>) => ({
  type: 'object',
  properties,
});

function numbers(defs: Record<string, unknown>) {
  return new TextNumbers(defs as never, key => ({
    name: `${key.toLowerCase()}Numbers`,
    file: 'shop/textNumbers.ts',
  }));
}

describe('the exact numbers of a schema', () => {
  it('marks nothing where no number is exact, recursion included', () => {
    const derived = numbers({
      Node: object({ count: { type: 'integer' }, next: ref('Node') }),
    });
    expect(derived.marks(ref('Node'), 'Node')).toBeUndefined();
    expect(derived.marks('not a schema', 'x')).toBeUndefined();
    expect(derived.marks(ref('Gone'), 'x')).toBeUndefined();
    expect(derived.declarations()).toEqual([]);
  });

  it('declares each recursive type once, in the order of their names, mutual recursion included', () => {
    const derived = numbers({
      B: object({ amount: long, a: ref('A') }),
      A: object({
        amount: long,
        b: ref('B'),
        self: { type: 'array', items: ref('A') },
      }),
    });
    expect(derived.marks(ref('A'), 'A')).toEqual({
      members: {
        amount: true,
        b: {
          members: {
            amount: true,
            a: { lazy: { name: 'aNumbers', file: 'shop/textNumbers.ts' } },
          },
        },
        self: {
          members: {
            '*': { lazy: { name: 'aNumbers', file: 'shop/textNumbers.ts' } },
          },
        },
      },
    });
    expect(derived.declarations().map(declared => declared.name)).toEqual([
      'aNumbers',
    ]);
  });

  it('keeps every part of an allOf, and members said beside the cases in every case', () => {
    const derived = numbers({
      Tagged: {
        allOf: [
          {
            oneOf: [
              object({ kind: { enum: ['a'] }, amount: decimal }),
              object({ kind: { enum: ['b'] }, amount: { type: 'integer' } }),
            ],
          },
          object({ revision: long }),
        ],
      },
    });
    expect(derived.marks(ref('Tagged'), 'Tagged')).toEqual({
      by: 'kind',
      cases: {
        a: { members: { amount: true, revision: true } },
        b: { members: { revision: true } },
      },
    });
  });

  it('reads a number beside discriminated objects by its kind', () => {
    const derived = numbers({
      Price: {
        anyOf: [
          decimal,
          object({ kind: { const: 'fixed' }, amount: decimal }),
          object({ kind: { const: 'rate' }, amount: double }),
        ],
      },
    });
    expect(derived.marks(ref('Price'), 'Price')).toEqual({
      or: { by: 'kind', cases: { fixed: { members: { amount: true } } } },
    });
  });

  it('refuses alternatives that disagree at their root, or whose discriminants repeat', () => {
    expect(() =>
      numbers({ Either: { anyOf: [long, { type: 'integer' }] } }).marks(
        ref('Either'),
        'Either',
      ),
    ).toThrow(
      new GeneratorError(
        'specification',
        'The alternatives of Either disagree at their root: one is `int64`, read as its exact text, another `integer`, read as a number, and no member with a constant value tells them apart. Give the alternatives a discriminator (@JsonTypeInfo with @JsonSubTypes), or the same number format.',
      ),
    );
    expect(() =>
      numbers({
        Same: {
          anyOf: [
            object({
              kind: { const: 'x' },
              n: { items: decimal, type: 'array' },
            }),
            object({
              kind: { const: 'x' },
              n: { items: double, type: 'array' },
            }),
          ],
        },
      }).marks(ref('Same'), 'Same'),
    ).toThrow('disagree at n.*: one is `decimal`');
  });

  it('refuses an array beside discriminated objects when its items hold exact numbers, naming it', () => {
    const union = (items: unknown) => ({
      anyOf: [
        { type: 'array', items },
        object({ kind: { const: 'a' }, amount: decimal }),
        object({ kind: { const: 'b' }, amount: double }),
      ],
    });
    expect(() =>
      numbers({ Mixed: union(decimal) }).marks(ref('Mixed'), 'Mixed'),
    ).toThrow(
      new GeneratorError(
        'specification',
        'The alternatives of Mixed are told apart by their `kind`, but alternative 1 has none and holds exact numbers no reader could choose by it. Give it a `kind` too, or take it out of the union.',
      ),
    );
    // Items of plain numbers lose nothing: the union is marked by its cases.
    expect(
      numbers({ Plain: union(double) }).marks(ref('Plain'), 'Plain'),
    ).toEqual({ by: 'kind', cases: { a: { members: { amount: true } } } });
  });

  it('refuses a recursive array or map beside discriminated objects once the type it reaches is read', () => {
    const tagged = [
      object({ kind: { const: 'leaf' }, amount: decimal }),
      object({ kind: { const: 'plain' }, amount: double }),
    ];
    const refusal =
      'The alternatives of Node are told apart by their `kind`, but alternative 1 has none and holds exact numbers no reader could choose by it. Give it a `kind` too, or take it out of the union.';
    // Its items are Node, still being read when the union is.
    expect(() =>
      numbers({
        Node: { anyOf: [{ type: 'array', items: ref('Node') }, ...tagged] },
      }).marks(ref('Node'), 'Node'),
    ).toThrow(new GeneratorError('specification', refusal));
    expect(() =>
      numbers({
        Node: {
          anyOf: [
            { type: 'object', additionalProperties: ref('Node') },
            ...tagged,
          ],
        },
      }).marks(ref('Node'), 'Node'),
    ).toThrow(new GeneratorError('specification', refusal));
  });

  it('generates a recursive union of plain numbers', () => {
    const derived = numbers({
      Node: {
        anyOf: [
          { type: 'array', items: ref('Node') },
          object({ kind: { const: 'a' }, amount: double }),
          object({ kind: { const: 'b' }, amount: { type: 'integer' } }),
        ],
      },
    });
    expect(derived.marks(ref('Node'), 'Node')).toBeUndefined();
    expect(derived.declarations()).toEqual([]);
  });

  it('keeps what an allOf says beside a recursive type it is still reading', () => {
    const derived = numbers({
      Node: object({
        amount: long,
        next: { allOf: [ref('Node'), object({ fee: decimal })] },
        plain: { allOf: [ref('Node'), object({ note: { type: 'string' } })] },
      }),
    });
    const lazy = (name: string) => ({
      lazy: { name, file: 'shop/textNumbers.ts' },
    });
    expect(derived.marks(ref('Node'), 'Node')).toEqual({
      members: {
        amount: true,
        next: lazy('nodeNumbers2'),
        plain: lazy('nodeNumbers'),
      },
    });
    // Node with its fee recurs as Node does: a constant of its own.
    expect(
      derived
        .declarations()
        .map(({ key, beside, name, marks }) => [key, beside, name, marks]),
    ).toEqual([
      [
        'Node',
        undefined,
        'nodeNumbers',
        {
          members: {
            amount: true,
            next: lazy('nodeNumbers2'),
            plain: lazy('nodeNumbers'),
          },
        },
      ],
      [
        'Node',
        true,
        'nodeNumbers2',
        {
          members: {
            amount: true,
            next: lazy('nodeNumbers2'),
            plain: lazy('nodeNumbers'),
            fee: true,
          },
        },
      ],
    ]);
  });

  it('refuses a union of a discriminated object and another object it cannot tell apart', () => {
    const tagged = {
      oneOf: [
        object({ kind: { const: 'a' }, n: decimal }),
        object({ kind: { const: 'b' }, n: double }),
      ],
    };
    expect(() =>
      numbers({ Mixed: { anyOf: [tagged, object({ m: long })] } }).marks(
        ref('Mixed'),
        'Mixed',
      ),
    ).toThrow('one is an object told apart by a member, another is not');
  });

  it('merges a recursive type with what it already knows of it, and refuses one it is still reading', () => {
    const known = numbers({
      Leaf: object({ amount: long }),
      Holder: object({
        either: { anyOf: [ref('Leaf'), object({ amount: long })] },
      }),
    });
    expect(known.marks(ref('Leaf'), 'Leaf')).toEqual({
      members: { amount: true },
    });
    expect(known.marks(ref('Holder'), 'Holder')).toEqual({
      members: { either: { members: { amount: true } } },
    });
    expect(() =>
      numbers({
        Tree: object({
          next: {
            anyOf: [ref('Tree'), object({ amount: { type: 'integer' } })],
          },
          amount: long,
        }),
      }).marks(ref('Tree'), 'Tree'),
    ).toThrow('one is `Tree`, a recursive type not yet read');
  });
});
