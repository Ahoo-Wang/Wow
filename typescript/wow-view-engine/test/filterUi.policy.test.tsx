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

import { node, configuration } from './fixtures/filterPanel.js';
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  act,
} from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { StrictMode } from 'react';
import { FilterOperator as Op } from '@ahoo-wang/fetcher-wow';
import { FilterPanel } from '../src/filter/FilterPanel.js';
import type {
  FilterPanelToolbarProps,
  FilterComponentProps,
} from '../src/filter/filterReactTypes.js';
import { fields, builtinCompiler } from './fixtures/filterPanel.js';
import { PortalThemeExample } from './fixtures/portalTheme.js';
import { FilterSearchSelect } from '../src/filter/FilterSearchSelect.js';
import {
  Tooltip,
  TooltipProvider,
  TooltipTrigger,
  TooltipContent,
} from '../src/components/ui/tooltip.js';
afterEach(cleanup);

it('tracks a theme change while the same popover remains open', async () => {
  const view = render(<PortalThemeExample kind="popover" appearance="dark" />);
  fireEvent.click(screen.getByRole('button', { name: '打开弹层' }));
  const popup = await screen.findByRole('dialog');
  await waitFor(() =>
    expect(popup.closest('[data-theme]')?.getAttribute('data-theme')).toBe(
      'dark',
    ),
  );
  view.rerender(<PortalThemeExample kind="popover" appearance="light" />);
  await waitFor(() =>
    expect(popup.closest('[data-theme]')?.getAttribute('data-theme')).toBe(
      'light',
    ),
  );
});

it('rejects retained toolbar mode callbacks after the panel becomes disabled', () => {
  let toolbar: FilterPanelToolbarProps | undefined;
  const changed = vi.fn();
  const draw = (disabled: boolean) => (
    <FilterPanel
      fields={fields}
      defaultValue={configuration(node('EQ', 'amount', { value: 1 }))}
      onApply={() => {}}
      onChange={next => changed(next.mode)}
      disabled={disabled}
      renderToolbar={props => {
        toolbar = props;
        return null;
      }}
    />
  );
  const view = render(draw(false));
  const retained = toolbar!.onModeChange;
  view.rerender(draw(true));
  act(() => retained('advanced'));
  expect(changed).not.toHaveBeenCalled();
});

it('does not select from an already-open search popup after becoming disabled', async () => {
  const changed = vi.fn();
  const draw = (disabled: boolean) => (
    <FilterSearchSelect
      label="Choice"
      options={[{ value: 'a', label: 'Alpha' }]}
      onValueChange={changed}
      disabled={disabled}
    />
  );
  const view = render(draw(false));
  fireEvent.click(screen.getByRole('combobox', { name: 'Choice' }));
  const option = await screen.findByRole('option', { name: 'Alpha' });
  view.rerender(draw(true));
  fireEvent.pointerDown(option, { pointerType: 'mouse' });
  fireEvent.click(option);
  expect(changed).not.toHaveBeenCalled();
});

it('keeps tooltip descriptions linked when the content ID is supplied', async () => {
  render(
    <TooltipProvider>
      <Tooltip open>
        <TooltipTrigger>Trigger</TooltipTrigger>
        <TooltipContent id="caller-tooltip">Description</TooltipContent>
      </Tooltip>
    </TooltipProvider>,
  );
  const popup = await screen.findByRole('tooltip');
  expect(
    screen
      .getByRole('button', { name: 'Trigger' })
      .getAttribute('aria-describedby')
      ?.split(' '),
  ).toContain(popup.id);
});

it('checks the current operator policy before honoring a retained complete-filter callback', () => {
  let contract: FilterComponentProps | undefined;
  const changed = vi.fn();
  function Custom(props: FilterComponentProps) {
    contract = props;
    return <span>Custom</span>;
  }
  const draw = (restricted: boolean) => (
    <FilterPanel
      fields={[{ ...fields[0], editor: { name: 'custom' } }]}
      defaultValue={configuration(
        node('EQ', 'amount', { value: 1 }, { name: 'custom' }),
      )}
      onApply={() => {}}
      onChange={changed}
      allowedOperators={restricted ? [Op.EQ] : [Op.EQ, Op.GTE]}
      extensions={{
        filters: {
          custom: {
            component: Custom,
            render: 'filter',
            modes: ['simple'],
            ...builtinCompiler,
          },
        },
      }}
    />
  );
  const view = render(draw(false));
  const retained = contract!.onOperatorChange;
  view.rerender(draw(true));
  act(() => retained(Op.GTE));
  expect(changed).not.toHaveBeenCalled();
});

it('keeps tooltip IDs connected through caller ID changes, closing, remounting and StrictMode', async () => {
  const draw = (id?: string, open = true, content = true) => (
    <StrictMode>
      <TooltipProvider>
        <Tooltip open={open}>
          <TooltipTrigger aria-describedby="host-description">
            Tooltip trigger
          </TooltipTrigger>
          {content && (
            <TooltipContent id={id}>Tooltip description</TooltipContent>
          )}
        </Tooltip>
        <span id="host-description">Host description</span>
      </TooltipProvider>
    </StrictMode>
  );
  const view = render(draw());
  const trigger = screen.getByRole('button', { name: 'Tooltip trigger' });
  const check = async (id?: string) => {
    const popup = await screen.findByRole('tooltip');
    expect(popup.id).toBeTruthy();
    if (id) expect(popup.id).toBe(id);
    const described = trigger.getAttribute('aria-describedby')!.split(' ');
    expect(described).toContain('host-description');
    expect(described).toContain(popup.id);
  };
  await check();
  view.rerender(draw('custom-a'));
  await check('custom-a');
  view.rerender(draw('custom-b'));
  await check('custom-b');
  view.rerender(draw('custom-b', false));
  await waitFor(() => expect(screen.queryByRole('tooltip')).toBeNull());
  view.rerender(draw('custom-b'));
  await check('custom-b');
  view.rerender(draw(undefined, true, false));
  expect(screen.queryByRole('tooltip')).toBeNull();
  view.rerender(draw());
  await check();
});
