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

import {
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import {
  DEFAULT_RUNTIME_LIMITS,
  MemoryViewStore,
  ViewEngine,
} from '../src/index.js';
import type {
  DataViewDefinition,
  Issue,
  RecordViewConfig,
  ViewInstance,
} from '../src/index.js';
import { admittedPageSize } from '../src/react/recordEdits.js';
import { DataWorkbench } from '../src/ui/index.js';
import { configRemedy } from '../src/ui/workbench/configRemedy.js';
import {
  ordersDefinition,
  recordConfig,
  testSource,
  resourcesOf,
} from './fixtures.js';
import { mine } from './fixtures/ui.js';

afterEach(cleanup);

const error = (path: Issue['path']): Issue => ({
  code: 'x',
  severity: 'error',
  path,
});

describe('configRemedy', () => {
  it('names the place the first fixable error is about', () => {
    expect(configRemedy([error(['pageSize'])])).toBe('page-size');
    expect(configRemedy([error(['layout'])])).toBe('layout');
    expect(configRemedy([error(['sort', 0, 'field'])])).toBe('sort');
    expect(configRemedy([error(['card', 'title'])])).toBe('card');
    expect(configRemedy([error(['table', 'columns', 0, 'field'])])).toBe(
      'columns',
    );
    expect(configRemedy([error(['summaries', 0])])).toBe('columns');
  });

  it('skips what it has no remedy for, and warnings', () => {
    expect(configRemedy([error(['filter']), error(['sort', 0])])).toBe('sort');
    expect(
      configRemedy([{ ...error(['pageSize']), severity: 'warning' }]),
    ).toBeNull();
    expect(configRemedy([error([])])).toBeNull();
  });
});

describe('admittedPageSize', () => {
  const ladder = [10, 20, 50, 100];

  it('is nothing for a size the limits admit', () => {
    expect(admittedPageSize(ladder, 100, 100)).toBeNull();
    expect(admittedPageSize(ladder, 100, 37)).toBeNull();
  });

  it('is the admitted rung nearest a refused size', () => {
    expect(admittedPageSize(ladder, 100, 200)).toBe(100);
    expect(admittedPageSize(ladder, 50, 200)).toBe(50);
    expect(admittedPageSize(ladder, 100, 0)).toBe(10);
    expect(admittedPageSize(ladder, 100, 2.5)).toBe(10);
  });
});

/**
 * The error strip's way out of a config that will not run goes where the
 * error is (second review R1-P1-3): it used to be the column settings
 * whatever the error was, so a page size over the limit could not be fixed
 * from the screen at all.
 */
describe('the way out of a config that will not run', () => {
  function workbench(
    config: Partial<RecordViewConfig>,
    definition: DataViewDefinition = ordersDefinition(),
  ) {
    const instance: ViewInstance = {
      ...mine,
      config: recordConfig(config),
    };
    const engine = new ViewEngine({
      resources: resourcesOf([definition], () => testSource()),
      store: new MemoryViewStore({ instances: [instance] }),
    });
    render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId={instance.id}
      />,
    );
  }

  it('sets an admitted page size in one press', async () => {
    const user = userEvent.setup();
    workbench({ pageSize: 200 });
    const max = DEFAULT_RUNTIME_LIMITS.maxPageSize;

    const strip = await screen.findByRole('alert');
    expect(strip.textContent).toContain(`The page size cannot exceed ${max}.`);
    await user.click(
      within(strip).getByRole('button', { name: `Show ${max} per page` }),
    );

    // It runs: the rows come, and the strip goes.
    expect(await screen.findByRole('table')).toBeTruthy();
    await waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
  });

  it('switches to a layout the definition offers', async () => {
    const user = userEvent.setup();
    const base = ordersDefinition();
    workbench(
      { layout: 'card' },
      { ...base, record: { ...base.record!, layouts: ['table'] } },
    );

    const strip = await screen.findByRole('alert');
    await user.click(
      within(strip).getByRole('button', { name: 'Switch to Table' }),
    );
    expect(await screen.findByRole('table')).toBeTruthy();
  });

  it('opens the sort settings for a sort it refuses', async () => {
    const user = userEvent.setup();
    workbench({ sort: [{ field: 'nope', direction: 'ASC' }] });

    const strip = await screen.findByRole('alert');
    await user.click(
      within(strip).getByRole('button', { name: 'Open sort settings' }),
    );
    expect(await screen.findByRole('dialog', { name: 'Sort' })).toBeTruthy();
  });

  it('opens the card settings for a card it refuses', async () => {
    const user = userEvent.setup();
    workbench({ layout: 'card', card: { title: 'nope', fields: [] } });

    const strip = await screen.findByRole('alert');
    await user.click(
      within(strip).getByRole('button', { name: 'Open card settings' }),
    );
    expect(
      await screen.findByRole('dialog', { name: 'Card settings' }),
    ).toBeTruthy();
  });

  it('keeps the column settings for a column it refuses', async () => {
    workbench({ table: { columns: [{ field: 'removedColumn' }] } });

    const strip = await screen.findByRole('alert');
    expect(
      within(strip).getByRole('button', { name: 'Open column settings' }),
    ).toBeTruthy();
  });
});
