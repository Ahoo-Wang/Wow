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
  fireEvent,
  render,
  renderHook,
  screen,
} from '@testing-library/react';
import { afterEach, expect, it } from 'vitest';
import type { CSSProperties } from 'react';
import { Calendar } from '../src/components/ui/calendar.js';
import {
  InputGroup,
  InputGroupAddon,
  InputGroupInput,
} from '../src/components/ui/input-group.js';
import { usePortalTheme } from '../src/lib/usePortalTheme.js';
import {
  compileFilterConfiguration,
  newFilterNode,
  createFilterConfiguration,
} from '../src/filter/filterCore.js';
import { FilterOperator } from '@ahoo-wang/fetcher-wow';
afterEach(cleanup);

it('shows requested ISO week numbers alongside the calendar dates', () => {
  render(<Calendar month={new Date(2026, 8, 1)} showWeekNumber ISOWeek />);
  expect(screen.getByText('36')).toBeTruthy();
  expect(screen.getByLabelText('Week 36')).toBeTruthy();
});

it('focuses the input from its addon without stealing focus from an addon button', () => {
  render(
    <InputGroup>
      <InputGroupAddon>
        <span>Amount</span>
        <button>
          <span>Action</span>
        </button>
      </InputGroupAddon>
      <InputGroupInput aria-label="amount" />
    </InputGroup>,
  );
  const button = screen.getByRole('button');
  button.focus();
  fireEvent.click(screen.getByText('Action'));
  expect(document.activeElement).toBe(button);
  fireEvent.click(screen.getByText('Amount'));
  expect(document.activeElement).toBe(screen.getByLabelText('amount'));
});

it('copies portal theme variables while excluding per-element Tailwind runtime state', () => {
  function Theme() {
    const { scope, theme } = usePortalTheme(true);
    return (
      <span
        ref={scope}
        style={
          {
            '--fve-primary': 'red',
            '--fve-tw-ring-color': 'blue',
            '--unrelated': 'green',
            fontFamily: 'serif',
          } as CSSProperties
        }
      >
        <output data-testid="theme">{JSON.stringify(theme.style)}</output>
      </span>
    );
  }
  render(<Theme />);
  const style = JSON.parse(screen.getByTestId('theme').textContent!);
  expect(style['--fve-primary']).toBe('red');
  expect(style.fontFamily).toBe('serif');
  expect(style).not.toHaveProperty('--fve-tw-ring-color');
  expect(style).not.toHaveProperty('--unrelated');
});

it('allows the portal scope to remain unattached', () => {
  const { result } = renderHook(() => usePortalTheme(true));
  expect(result.current.theme).toEqual({ style: {} });
});

it('rejects a non-string time zone at the public compiler boundary', () => {
  const result = compileFilterConfiguration(
    createFilterConfiguration(newFilterNode(FilterOperator.MATCH_ALL)),
    [],
    undefined,
    undefined,
    42 as unknown as string,
  );
  expect(result.expression).toBeUndefined();
  expect(result.errors).toEqual([
    expect.objectContaining({ message: '时区必须是字符串' }),
  ]);
});
