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
  MemoryViewStore,
  ViewEngine,
  say,
  text,
  textKeyOf,
  withText,
} from '../src/index.js';
import { ordersDefinition, testSource, resourcesOf } from './fixtures.js';

/** host-integration.md 3.1: a definition written in keys, said in words. */
describe('text', () => {
  it('is a string that says which key it stands for', () => {
    const key = text('orders.title');
    expect(typeof key).toBe('string');
    expect(textKeyOf(key)).toBe('orders.title');
    expect(textKeyOf('Orders')).toBeNull();
  });

  it('says every key in a value, and keeps what holds none', () => {
    const plain = { title: 'Orders', fields: [{ label: 'Order' }] };
    expect(withText(plain, () => 'never')).toBe(plain);
    const missing: unknown[] = [];
    const said = withText(
      {
        title: text('title'),
        fields: [{ label: text('order') }, { label: 'Amount' }],
        views: [{ title: text('gone') }],
      },
      key => ({ title: 'Orders', order: 'Order' })[key],
      (key, path) => missing.push([key, path]),
    );
    expect(said).toEqual({
      title: 'Orders',
      fields: [{ label: 'Order' }, { label: 'Amount' }],
      views: [{ title: 'gone' }],
    });
    expect(missing).toEqual([['gone', ['views', 0, 'title']]]);
  });

  it('says a label in a catalogue or a resolver, a key it lacks as the key (`say`, D2)', () => {
    const label = `${text('orders.id')} · ${text('orders.none')}`;
    expect(say(label, { 'orders.id': 'Order' })).toBe('Order · orders.none');
    expect(
      say(label, key => (key === 'orders.id' ? '订单号' : undefined)),
    ).toBe('订单号 · orders.none');
    // A catalogue's inherited members are no words.
    expect(say(text('toString'), {})).toBe('toString');
    const plain = 'Order';
    expect(say(plain, {})).toBe(plain);
  });

  it('says a key where code has put it inside a longer string', () => {
    const words: Record<string, string> = { field: '状态', element: '原因' };
    const joined = `${text('field')} · ${text('element')}`;
    expect(textKeyOf(joined)).toBeNull();
    expect(withText({ label: joined }, key => words[key])).toEqual({
      label: '状态 · 原因',
    });
    const missing: string[] = [];
    expect(
      withText(
        `${text('gone')} is empty`,
        () => undefined,
        key => missing.push(key),
      ),
    ).toBe('gone is empty');
    expect(missing).toEqual(['gone']);
  });

  it('checks the words given at registration, keeping the keys, a key with no words warned of', () => {
    const reported: string[] = [];
    const base = ordersDefinition();
    const engine = new ViewEngine({
      resources: resourcesOf(
        [
          {
            ...base,
            title: text('orders.title'),
            fields: base.fields.map(field =>
              field.name === 'id'
                ? { ...field, label: text('orders.id') }
                : field,
            ),
          },
        ],
        () => testSource(),
      ),
      text: key => (key === 'orders.title' ? '订单' : undefined),
      store: new MemoryViewStore(),
      onIssue: found => reported.push(found.code),
    });
    const definition = engine.definitions.get('orders');
    // Handed out as declared; the words it started with are the screen's
    // to fall back on (`startingWord`, `useSay`).
    expect(definition?.title).toBe(text('orders.title'));
    expect(engine.startingWord('orders.title')).toBe('订单');
    expect(
      definition?.kind === 'data' ? definition.fields[0].label : null,
    ).toBe(text('orders.id'));
    expect(reported).toEqual(['definition.text.unknown']);
    expect(engine.definitionIssues('orders')).toEqual([
      expect.objectContaining({
        code: 'definition.text.unknown',
        params: { key: 'orders.id' },
        path: ['fields', 0, 'label'],
        severity: 'warning',
      }),
    ]);
  });
});
