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
 * The view store service's rules the port's stores answer alike: what the
 * Wow view store service (`platform/view-store`) refuses or records nothing
 * for, which `MemoryViewStore` answers as it does, so a test on the memory
 * sees what production gives (#7067, D482 ⑧), a call whose signal is
 * aborted, which reaches nothing, and the page bound of a list
 * across the system views (#7041). Run beside the port's suite
 * (`viewStoreConformance.ts`) by every subject that stands for the service:
 * `MemoryViewStore` here, **WowViewStore** on the store's fake service and
 * on the real one (`typescript/integration-test`).
 */

import { describe, expect, it } from 'vitest';
import type {
  ViewConfig,
  ViewInstance,
  ViewScope,
} from '../../src/model/index.js';
import {
  ALICE,
  everyView,
  freshDefinition,
  ids,
  recordConfig,
  refusal,
  type ViewStoreConformance,
  write,
} from './viewStoreConformance.js';

/** Registers the service's rules for one subject, in one `describe`. */
export function describeViewStoreServiceRules(
  subject: ViewStoreConformance,
): void {
  const { capabilities } = subject;
  const own: ViewScope = capabilities.personalViews ? 'personal' : 'shared';

  async function setup() {
    const open = await subject.connect();
    const definitionId = freshDefinition();
    const alice = open(ALICE);
    const create = (title: string, scope: ViewScope = own) =>
      alice.create(
        { definitionId, title, scope, config: recordConfig() },
        write(),
      );
    return { alice, definitionId, create };
  }

  describe(`ViewStore conformance, the view store service's rules: ${subject.name}`, () => {
    it('answers a rename to the title the view has with the view as it is, its revision unmoved', async () => {
      // View.onRename records nothing for the title the view has.
      const { alice, create } = await setup();
      const made = await create('Closed orders');
      const renamed = await alice.rename(
        made.id,
        ' Closed orders ',
        made.revision,
        write(),
      );
      expect(renamed.revision).toBe(made.revision);
      // A writer holding the revision read before still writes.
      const saved = await alice.save(
        made.id,
        recordConfig(50),
        made.revision,
        write(),
      );
      expect(saved.revision).not.toBe(made.revision);
    });

    it('answers preferences set to what is stored with them as they are, their revision unmoved', async () => {
      // ViewPreferences.onSet records nothing for what is stored.
      const { alice, definitionId, create } = await setup();
      const made = await create('Mine');
      const first = await alice.setPreferences(
        definitionId,
        { order: [made.id], defaultInstanceId: null, revision: '0' },
        write(),
      );
      const again = await alice.setPreferences(
        definitionId,
        { ...first },
        write(),
      );
      expect(again.revision).toBe(first.revision);
      const changed = await alice.setPreferences(
        definitionId,
        { ...first, defaultInstanceId: made.id },
        write(),
      );
      expect(changed.revision).not.toBe(first.revision);
    });

    it('refuses a config whose kind is no view kind, and a blank definition id, as INVALID', async () => {
      // ViewConfigs.problemOf and definitionProblem.
      const { alice, definitionId } = await setup();
      const chart = {
        ...recordConfig(),
        kind: 'chart',
      } as unknown as ViewConfig;
      await refusal(
        alice.create(
          { definitionId, title: 'Chart', scope: own, config: chart },
          write(),
        ),
        'INVALID',
      );
      await refusal(
        alice.create(
          {
            definitionId: '  ',
            title: 'Nowhere',
            scope: own,
            config: recordConfig(),
          },
          write(),
        ),
        'INVALID',
      );
    });

    it('refuses a list cursor it did not issue as INVALID', async () => {
      const { alice, definitionId } = await setup();
      await refusal(
        alice.list(definitionId, { cursor: 'not-a-cursor' }),
        'INVALID',
      );
    });

    it('refuses a call whose signal is aborted as UNAVAILABLE, and writes nothing', async () => {
      // WowViewStore hands the signal to fetch: such a call never reaches
      // the service, and a request that reached nothing is UNAVAILABLE.
      const { alice, definitionId, create } = await setup();
      const made = await create('Kept');
      const renaming = write();
      const renamed = await alice.rename(
        made.id,
        'Kept again',
        made.revision,
        renaming,
      );
      const preferences = await alice.setPreferences(
        definitionId,
        { order: [made.id], defaultInstanceId: null, revision: '0' },
        write(),
      );
      const signal = AbortSignal.abort(new Error('The host left the page'));
      const aborted = { ...write(), signal };
      const calls: [string, () => Promise<unknown>][] = [
        ['get', () => alice.get(made.id, { signal })],
        ['list', () => alice.list(definitionId, { signal })],
        [
          'getPreferences',
          () => alice.getPreferences(definitionId, { signal }),
        ],
        [
          'create',
          () =>
            alice.create(
              {
                definitionId,
                title: 'Never',
                scope: own,
                config: recordConfig(),
              },
              aborted,
            ),
        ],
        [
          'save',
          () =>
            alice.save(made.id, recordConfig(50), renamed.revision, aborted),
        ],
        [
          'rename',
          () => alice.rename(made.id, 'Gone', renamed.revision, aborted),
        ],
        // A replay of a write that landed is sent again: it is no answer
        // the store may give without asking the service.
        [
          'rename replayed',
          () =>
            alice.rename(made.id, 'Kept again', made.revision, {
              ...renaming,
              signal,
            }),
        ],
        ['delete', () => alice.delete(made.id, renamed.revision, aborted)],
        [
          'setPreferences',
          () =>
            alice.setPreferences(
              definitionId,
              { ...preferences, defaultInstanceId: made.id },
              aborted,
            ),
        ],
      ];
      if (capabilities.changeAudience && alice.changeAudience) {
        const changeAudience = alice.changeAudience.bind(alice);
        const other = own === 'personal' ? 'shared' : 'personal';
        calls.push([
          'changeAudience',
          () => changeAudience(made.id, other, renamed.revision, aborted),
        ]);
      }
      // The port promises the code. What `cause` holds is what the request
      // threw, which WowViewStore's clients do not all throw alike (the
      // preferences' snapshot query wraps the reason in fetcher's
      // ExchangeError), so no cause is asserted.
      for (const [, call] of calls) await refusal(call(), 'UNAVAILABLE');
      const read = await alice.get(made.id);
      expect([read.title, read.revision, read.scope]).toEqual([
        'Kept again',
        renamed.revision,
        own,
      ]);
      expect(
        (await everyView(alice, definitionId)).map(view => view.id),
      ).toEqual([made.id]);
      expect(await alice.getPreferences(definitionId)).toEqual(preferences);
    });

    it('reads a list cursor before the signal: one it did not issue is INVALID, aborted or not', async () => {
      // WowViewStore reads its cursor before it sends anything.
      const { alice, definitionId } = await setup();
      await refusal(
        alice.list(definitionId, {
          cursor: 'not-a-cursor',
          signal: AbortSignal.abort(),
        }),
        'INVALID',
      );
    });

    it.skipIf(
      capabilities.pageSize === undefined || !capabilities.storedSystemViews,
    )('bounds every page by its size, the system views too', async () => {
      const size = capabilities.pageSize!;
      const { alice, definitionId, create } = await setup();
      const made: ViewInstance[] = [];
      // More system views than one page holds, then one of the caller's own.
      for (let at = 0; at <= size; at += 1)
        made.push(await create(`System ${at}`, 'system'));
      made.push(await create('Mine'));

      const first = await alice.list(definitionId);
      expect(first.items.length).toBeLessThanOrEqual(size);
      expect(first.nextCursor).not.toBeNull();
      const listed = ids(await everyView(alice, definitionId)).filter(id =>
        made.some(view => view.id === id),
      );
      expect(listed).toEqual(made.map(view => view.id));
    });
  });
}
