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

import { act, cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type * as React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  actions,
  type RecordLaneView,
  type RecordRow,
} from '../src/index.js';
import { memorySource } from '../src/testing/index.js';
import { DataWorkbench } from '../src/ui/index.js';
import type * as KanbanDragModule from '../src/ui/record/kanbanDrag.js';
import { resourcesOf } from './fixtures.js';
import { DEALS, dealsDefinition, dealsKanban } from './fixtures/kanban.js';

// jsdom cannot carry a pointer drag; the drag's shell hands its drop to the
// test instead, and the kanban around it is the real one.
const drag: {
  lanes: readonly RecordLaneView[];
  onDrop?: (row: RecordRow, from: string, to: RecordLaneView) => void;
} = { lanes: [] };

vi.mock('../src/ui/record/kanbanDrag.js', async importOriginal => {
  const actual = await importOriginal<typeof KanbanDragModule>();
  return {
    ...actual,
    KanbanDrag: ({
      lanes,
      onDrop,
      children,
    }: {
      lanes: readonly RecordLaneView[];
      onDrop: (row: RecordRow, from: string, to: RecordLaneView) => void;
      children: React.ReactNode;
    }) => {
      drag.lanes = lanes;
      drag.onDrop = onDrop;
      return children;
    },
  };
});

afterEach(cleanup);

const lane = (name: RegExp | string) => screen.findByRole('region', { name });

function laneOf(value: string): RecordLaneView {
  return drag.lanes.find(each => each.value === value)!;
}

describe('a card dropped on another lane', () => {
  it('stays there through a refresh landing mid-move, and after the move', async () => {
    const documents = DEALS.map(deal => ({ ...deal }));
    let finish = () => {};
    const declared = actions([
      {
        id: 'move',
        label: 'Change stage',
        moves: 'stage',
        run: async (row, input) => {
          await new Promise<void>(resolve => (finish = resolve));
          const found = documents.find(deal => deal.id === row.key);
          if (found) found.stage = String(input.stage);
        },
      },
    ]);
    const engine = new ViewEngine({
      resources: resourcesOf([dealsDefinition()], () =>
        memorySource(documents),
      ),
      store: new MemoryViewStore({ instances: [dealsKanban] }),
    });
    render(
      <DataWorkbench
        engine={engine}
        definitionId="deals"
        instanceId={dealsKanban.id}
        record={{ actions: declared }}
      />,
    );
    await lane('Lead, 3 records');
    const card = laneOf('LEAD').rows.find(row => row.key === 'd-1')!;

    act(() => drag.onDrop!(card, laneOf('LEAD').id, laneOf('WON')));
    expect(await lane('Won, 2 records')).toBeTruthy();
    expect(await lane('Lead, 2 records')).toBeTruthy();

    // A read lands while the move runs, the stage still the old one.
    await userEvent.click(screen.getByRole('button', { name: 'Refresh' }));
    expect(await lane('Won, 2 records')).toBeTruthy();
    expect(
      screen.queryByRole('region', { name: 'Lead, 3 records' }),
    ).toBeNull();

    // The move ends and its own read puts the card where it now is.
    await act(async () => finish());
    const won = await lane('Won, 2 records');
    expect(within(won).getByText('d-1')).toBeTruthy();
    expect(await lane('Lead, 2 records')).toBeTruthy();
    expect(documents.find(deal => deal.id === 'd-1')?.stage).toBe('WON');
  });
});
