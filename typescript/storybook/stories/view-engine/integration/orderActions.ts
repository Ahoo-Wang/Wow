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

import { actions, text, type RecordRow } from '@ahoo-wang/wow-view-engine';

/**
 * The order commands the page sends, one order at a time. Each resolves
 * once the order's snapshot shows it (`CommandStage.SNAPSHOT`), so the
 * refresh that follows reads the new state; each rejects when the service
 * refuses it. `wowOrderCommands` sends them to the service.
 */
export interface OrderCommands {
  ship(orderId: string): Promise<void>;
  cancel(orderId: string): Promise<void>;
}

const paid = (row: RecordRow) =>
  (row.data.state as { status?: string } | undefined)?.status === 'PAID';

// Step 4: the commands on an order, declared. The host says what, when and
// in what words; the engine places them — in the row, over a selection, in
// the record's detail — asks, runs them a few at a time and reports.
export const orderActions = (commands: OrderCommands) =>
  actions([
    {
      id: 'ship',
      label: text('orders.ship'),
      // A button in the row; the rest go behind the row's 「⋯」 menu.
      primary: true,
      // `true`, or why not: the reason shows on the disabled button, and a
      // selection is split into what can and what cannot take it.
      available: row => (paid(row) ? true : text('orders.notPaid')),
      // Routine, but not taken back once sent: asked for one order too, with
      // no danger tone; a selection is counted in the same question.
      confirm: { title: text('orders.shipTitle') },
      run: row => commands.ship(String(row.key)),
    },
    {
      id: 'cancel',
      label: text('orders.cancel'),
      tone: 'danger',
      available: row => (paid(row) ? true : text('orders.cannotCancel')),
      // Always asked, a single order too.
      confirm: {
        title: text('orders.cancelTitle'),
        body: text('orders.cancelBody'),
      },
      run: row => commands.cancel(String(row.key)),
    },
  ]);
