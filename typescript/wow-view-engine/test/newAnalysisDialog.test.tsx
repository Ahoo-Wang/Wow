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
 * A new analysis made inside the dashboard, alone (D22 C, R4 Q-04):
 * `NewAnalysisDialog` over an engine, without the workbench around it — the
 * data chosen first when there is more than one, and none offered that
 * cannot be analysed; 「改了就跑」 as the reader has it; a title that must be
 * there, Enter putting it on the board; a board with no room, which keeps
 * the dialog open to say so; and a view that could not be opened or a
 * question that failed. How the workbench reaches the dialog is
 * `dashboardExtensions.test.tsx`'s.
 */

import { StrictMode, useState } from 'react';
import {
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AggregationGroupType } from '@ahoo-wang/fetcher-wow';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  type DataViewDefinition,
  type ViewSource,
} from '../src/index.js';
import { NewAnalysisDialog, type NewAnalysis } from '../src/ui/index.js';
import { ordersDefinition, testSource } from './fixtures.js';

afterEach(cleanup);

const returns = ordersDefinition({ id: 'returns', title: 'Returns' });
/** Declares an analysis over a field it does not have: an error. */
const broken = ordersDefinition({
  id: 'broken',
  title: 'Broken',
  analysis: {
    count: true,
    fields: [
      { field: 'missing', groups: [AggregationGroupType.TERMS], functions: [] },
    ],
  },
});
/** Declares no analysis at all. */
const listOnly: DataViewDefinition = {
  ...ordersDefinition({ id: 'list-only', title: 'List only' }),
  analysis: undefined,
};

function engineOf(
  definitions: DataViewDefinition[] = [ordersDefinition()],
  source: ViewSource = testSource(),
) {
  return new ViewEngine({
    definitions,
    store: new MemoryViewStore(),
    resolveSource: () => source,
  });
}

/** The dialog, open until it asks to close; `onAdd` answers the board. */
function Harness({
  engine,
  onAdd = () => true,
}: {
  engine: ViewEngine;
  onAdd?(analysis: NewAnalysis): boolean;
}) {
  const [open, setOpen] = useState(true);
  return (
    <NewAnalysisDialog
      engine={engine}
      open={open}
      onOpenChange={setOpen}
      onAdd={onAdd}
    />
  );
}

type User = ReturnType<typeof userEvent.setup>;

function titleOf(dialog: HTMLElement): HTMLInputElement {
  return within(dialog).getByRole('textbox', {
    name: 'Title',
  }) as HTMLInputElement;
}

function addOf(dialog: HTMLElement): HTMLButtonElement {
  return within(dialog).getByRole('button', {
    name: 'Put on the dashboard',
  }) as HTMLButtonElement;
}

/** Waits for the analysis the dialog holds to answer and name itself. */
async function named(dialog: HTMLElement) {
  await waitFor(() =>
    expect(titleOf(dialog).value).toBe('By Warehouse · Record count'),
  );
}

async function chooseData(user: User, dialog: HTMLElement, name: string) {
  await user.click(within(dialog).getByRole('combobox', { name: 'Data' }));
  await user.click(await screen.findByRole('option', { name }));
}

describe('NewAnalysisDialog', () => {
  it('asks for the data first when there is more than one, offering only what can be analysed', async () => {
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    const engine = engineOf([ordersDefinition(), returns, broken, listOnly]);
    render(<Harness engine={engine} />);
    const dialog = await screen.findByRole('dialog');

    expect(
      within(dialog).getByRole('heading', { name: 'New analysis' }),
    ).toBeTruthy();
    expect(
      dialog.querySelector('[data-slot="new-analysis-pick"]'),
    ).not.toBeNull();
    expect(titleOf(dialog).disabled).toBe(true);
    expect(addOf(dialog).disabled).toBe(true);

    await user.click(within(dialog).getByRole('combobox', { name: 'Data' }));
    const listed = (await screen.findAllByRole('option')).map(
      option => option.textContent,
    );
    expect(listed).toEqual(['Orders', 'Returns']);
    await user.click(screen.getByRole('option', { name: 'Returns' }));

    expect(
      await within(dialog).findByRole('heading', {
        name: 'New analysis · Returns',
      }),
    ).toBeTruthy();
    await named(dialog);
    expect(dialog.querySelector('[data-slot="new-analysis-pick"]')).toBeNull();
    expect(engine.openRuntimes().map(open => open.definition.id)).toEqual([
      'returns',
    ]);

    // Another data lets the first go, and its title follows it.
    await user.type(titleOf(dialog), ' of returns');
    await chooseData(user, dialog, 'Orders');
    await within(dialog).findByRole('heading', {
      name: 'New analysis · Orders',
    });
    await named(dialog);
    await waitFor(() =>
      expect(engine.openRuntimes().map(open => open.definition.id)).toEqual([
        'orders',
      ]),
    );
  });

  it('says so when none of the data can be analysed', async () => {
    render(<Harness engine={engineOf([broken, listOnly])} />);
    const dialog = await screen.findByRole('dialog');
    expect(
      dialog.querySelector('[data-slot="new-analysis-none"]'),
    ).not.toBeNull();
    expect(within(dialog).queryByRole('combobox', { name: 'Data' })).toBeNull();
    expect(addOf(dialog).disabled).toBe(true);
  });

  it('puts it on the board with Enter, and not without a title', async () => {
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    const onAdd = vi.fn<(analysis: NewAnalysis) => boolean>(() => true);
    render(
      <StrictMode>
        <Harness engine={engineOf()} onAdd={onAdd} />
      </StrictMode>,
    );
    const dialog = await screen.findByRole('dialog');
    await named(dialog);

    await user.clear(titleOf(dialog));
    expect(addOf(dialog).disabled).toBe(true);
    await user.type(titleOf(dialog), '   {Enter}');
    expect(onAdd).not.toHaveBeenCalled();

    await user.clear(titleOf(dialog));
    await user.type(titleOf(dialog), '  Orders counted {Enter}');
    expect(onAdd).toHaveBeenCalledTimes(1);
    expect(onAdd.mock.calls[0][0]).toMatchObject({
      definitionId: 'orders',
      title: 'Orders counted',
      config: { kind: 'analysis' },
    });
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });

  it('stays open to say the board is full', async () => {
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    const onAdd = vi.fn<(analysis: NewAnalysis) => boolean>(() => false);
    render(<Harness engine={engineOf()} onAdd={onAdd} />);
    const dialog = await screen.findByRole('dialog');
    await named(dialog);

    await user.click(addOf(dialog));
    expect(onAdd).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('dialog')).toBe(dialog);
    const field = titleOf(dialog).closest('[data-slot="field"]');
    expect(field?.getAttribute('data-invalid')).toBe('true');
    expect(field?.textContent).toContain(
      'This dashboard holds as many panels as it can.',
    );
  });

  it('runs as the reader has 「改了就跑」 for the data, and keeps what they switch', async () => {
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    const engine = engineOf();
    await engine.setAutoRun('orders', false);
    render(<Harness engine={engine} />);
    const dialog = await screen.findByRole('dialog');
    const auto = await within(dialog).findByRole('checkbox', {
      name: 'Run automatically',
    });
    await waitFor(() =>
      expect(auto.getAttribute('aria-checked')).toBe('false'),
    );

    await user.click(auto);
    await waitFor(() => expect(auto.getAttribute('aria-checked')).toBe('true'));
    await waitFor(async () =>
      expect((await engine.preferences('orders')).autoRun).toBe(true),
    );
  });

  it('runs automatically when the reader’s preferences cannot be read', async () => {
    const engine = engineOf();
    vi.spyOn(engine, 'preferences').mockRejectedValue(new Error('offline'));
    render(<Harness engine={engine} />);
    const dialog = await screen.findByRole('dialog');
    const auto = await within(dialog).findByRole('checkbox', {
      name: 'Run automatically',
    });
    expect(auto.getAttribute('aria-checked')).toBe('true');
    await named(dialog);
  });

  it('says why the data could not be opened', async () => {
    const engine = engineOf();
    vi.spyOn(engine, 'create').mockImplementation(() => {
      throw new Error('no room');
    });
    render(<Harness engine={engine} />);
    const dialog = await screen.findByRole('dialog');
    const alert = await within(dialog).findByRole('alert');
    expect(alert.textContent).toContain('This data could not be opened');
    expect(titleOf(dialog).disabled).toBe(true);
    expect(addOf(dialog).disabled).toBe(true);
  });

  it('says the question failed, and asks again on Try again', async () => {
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    const source = testSource({
      aggregate: vi.fn(() => Promise.reject(new Error('down'))),
    });
    render(<Harness engine={engineOf([ordersDefinition()], source)} />);
    const dialog = await screen.findByRole('dialog');
    const retry = await within(dialog).findByRole('button', {
      name: 'Try again',
    });
    const asked = vi.mocked(source.aggregate).mock.calls.length;
    vi.mocked(source.aggregate).mockResolvedValue([{ orders: 2 }]);
    await user.click(retry);
    await waitFor(() =>
      expect(vi.mocked(source.aggregate).mock.calls.length).toBe(asked + 1),
    );
    await named(dialog);
  });
});
