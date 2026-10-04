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
import { text } from '@ahoo-wang/wow-view-engine';
import { actionHarness, admit } from '@ahoo-wang/wow-view-engine/testing';
import { orderActions } from './orderActions.js';
import {
  ORDERS_WORDS,
  ordersDefinition,
  ordersDescriptor,
} from './ordersDefinition.js';
import { sampleCommands, sampleOrders } from './sampleOrders.js';

/*
 * The walkthrough's declarations are quoted as the code a host copies, so
 * they are held to what a host's own tests hold theirs to: the definition
 * admitted over its committed descriptor, every key worded, and the actions
 * read by the engine's own rules.
 */
describe('the integration walkthrough', () => {
  it('declares a definition the engine admits, every key worded', () => {
    const words: Readonly<Record<string, string>> = ORDERS_WORDS;
    expect(
      admit(
        [ordersDefinition],
        { order: ordersDescriptor },
        { text: key => words[key] },
      ),
    ).toEqual([]);
  });

  it('offers 「发货」 on a paid order only, and says why not', () => {
    const orders = sampleOrders();
    const rows = orders.map(data => ({ key: String(data.aggregateId), data }));
    const harness = actionHarness(orderActions(sampleCommands(orders)), rows);
    expect(harness.state('ship', 'SO-0001').reason).toBeNull();
    expect(harness.state('ship', 'SO-0003').reason).toBe(
      text('orders.notPaid'),
    );
    expect(harness.bulk('ship').able).toHaveLength(
      orders.filter(
        order => (order.state as { status: string }).status === 'PAID',
      ).length,
    );
    // Shipping cannot be taken back: asked for one order too, as cancelling is.
    expect(harness.asks('ship', 'row').asks).toBe(true);
    expect(harness.asks('cancel', 'row').asks).toBe(true);
  });
});
