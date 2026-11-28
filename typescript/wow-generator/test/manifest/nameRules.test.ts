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

import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { GeneratorError } from '../../src/api/errors';
import type { ManifestDocument } from '../../src/manifest/document';
import {
  checkNames,
  CODE_SEGMENT_PATTERN,
  FRAMEWORK_NAME_PATTERN,
  isCode,
  isFrameworkName,
  isName,
  NAME_PATTERN,
} from '../../src/manifest/nameRules';

interface Cases {
  readonly accepted: readonly string[];
  readonly refused: readonly string[];
}

/**
 * The cases the Kotlin `NameRules` holds too (`:api`'s `NameRulesTest` reads
 * this file): a rule that changes on one side fails the other's test.
 */
const TABLE = JSON.parse(
  readFileSync(new URL('../fixtures/name-rules.json', import.meta.url), 'utf8'),
) as {
  readonly name: Cases & { readonly pattern: string };
  readonly code: Cases & { readonly segmentPattern: string };
  readonly frameworkName: Cases & { readonly pattern: string };
};

describe('the naming rules, as the Kotlin NameRules holds them', () => {
  it.each([
    ['a name', TABLE.name, isName],
    ['a code', TABLE.code, isCode],
    ["a name of the framework's own", TABLE.frameworkName, isFrameworkName],
  ] as const)(
    'accepts and refuses %s as the shared table says',
    (_, cases, rule) => {
      expect(cases.accepted.length).toBeGreaterThan(0);
      expect(cases.refused.length).toBeGreaterThan(0);
      expect(cases.accepted.filter(each => !rule(each))).toEqual([]);
      expect(cases.refused.filter(each => rule(each))).toEqual([]);
    },
  );

  it('spells the patterns as the shared table spells them', () => {
    expect(NAME_PATTERN).toBe(TABLE.name.pattern);
    expect(CODE_SEGMENT_PATTERN).toBe(TABLE.code.segmentPattern);
    expect(FRAMEWORK_NAME_PATTERN).toBe(TABLE.frameworkName.pattern);
  });
});

/** A Manifest of one context `shop` with the parts given. */
function shop(context: Record<string, unknown>): ManifestDocument {
  return {
    format: 1,
    contexts: [{ name: 'shop', ...context }],
  } as unknown as ManifestDocument;
}

const STATE = { schema: '#/$defs/a.State' };

/** The message `checkNames` refuses `manifest` with. */
function refusal(manifest: ManifestDocument): string {
  try {
    checkNames(manifest);
  } catch (error) {
    expect(error).toBeInstanceOf(GeneratorError);
    expect((error as GeneratorError).kind).toBe('specification');
    return (error as Error).message;
  }
  throw new Error('checkNames refused nothing');
}

describe('a Manifest holding a name the compiler refuses', () => {
  it('is refused naming the aggregate, the rule and that the Manifest did not come from the compiler', () => {
    expect(refusal(shop({ aggregates: [{ name: '1st', state: STATE }] }))).toBe(
      "The aggregate \"1st\" of the context shop is not a name: a name starts with a letter and holds only letters, digits, '_' and '-' ([A-Za-z][A-Za-z0-9_-]*), such as order-item; a dot separates a context, an aggregate and a type, and the generated contracts take an identifier from every name. The Wow compiler refuses it, so this Manifest did not come from one: correct what wrote it.",
    );
  });

  it("is refused naming a command or an event of an aggregate, and a context's command", () => {
    const aggregate = (parts: Record<string, unknown>) =>
      shop({ aggregates: [{ name: 'cart', state: STATE, ...parts }] });
    expect(
      refusal(aggregate({ commands: [{ name: 'Put Item', schema: 'x' }] })),
    ).toContain(
      'The command "Put Item" of the aggregate cart of the context shop is not a name',
    );
    expect(
      refusal(aggregate({ events: [{ name: '', schema: 'x' }] })),
    ).toContain(
      'The event "" of the aggregate cart of the context shop is not a name',
    );
    expect(
      refusal(shop({ commands: [{ name: 'shop.Tick', schema: 'x' }] })),
    ).toContain('The command "shop.Tick" of the context shop is not a name');
  });

  it("is refused naming a subscription whose processor's or handler's part is no name", () => {
    for (const name of ['Saga.', '.onPaid', 'Saga.on paid', '...', '']) {
      expect(refusal(shop({ subscriptions: [{ name }] })), name).toContain(
        `The subscription ${JSON.stringify(name)} of the context shop, a processor's name and a handler's joined by a dot, is not a name`,
      );
    }
  });

  it.each([
    ['shop.cart.', 'its last segment is empty (it ends in a dot)'],
    ['.closed', 'its first segment is empty (it starts with a dot)'],
    ['shop..closed', 'its segment 2 is empty (two dots in a row)'],
    ['shop.a b', 'its segment "a b" is not one'],
    ['', 'it is empty'],
  ])(
    'is refused naming the error code %j and what is wrong with it: %s',
    (code, fault) => {
      expect(refusal(shop({ errors: [{ code, status: 422 }] }))).toBe(
        `The error code ${JSON.stringify(code)} of the context shop is not a code: ${fault}; a code is segments joined by dots, each starting with a letter or a digit and holding only letters, digits, '_' and '-' ([A-Za-z0-9][A-Za-z0-9_-]*), such as shop.cart.out-of-stock. The Wow compiler refuses it, so this Manifest did not come from one: correct what wrote it.`,
      );
    },
  );

  it("takes the framework's own commands and events, and every name the rule accepts", () => {
    expect(() =>
      checkNames(
        shop({
          aggregates: [
            {
              name: 'order-item',
              state: STATE,
              commands: [
                { name: 'PlaceOrder', schema: 'x' },
                { name: 'wow.lifecycle.DeleteAggregate', schema: 'x' },
              ],
              events: [
                { name: 'OrderPlaced', schema: 'x' },
                { name: 'wow.lifecycle.AggregateDeleted', schema: 'x' },
              ],
            },
          ],
          commands: [{ name: 'wow.schedule.EnableJob', schema: 'x' }],
          subscriptions: [{ name: 'OrderSaga.onPlaced' }],
          errors: [{ code: 'shop.order-item.404', status: 404 }],
        }),
      ),
    ).not.toThrow();
    // A context that lists none of them has nothing to refuse.
    expect(() => checkNames(shop({}))).not.toThrow();
  });
});
