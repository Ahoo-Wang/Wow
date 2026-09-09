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
import { afterEach, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { FilterPanel } from '../src/filter/FilterPanel.js';
afterEach(cleanup);
it('links numeric validation to the focused input and clears the association when corrected', () => {
  render(
    <FilterPanel
      fields={[{ field: 'amount', label: '金额', type: 'number' }]}
      defaultValue={configuration(node('GTE', 'amount', { value: 0 }))}
      onApply={() => {}}
    />,
  );
  const input = screen.getByRole('textbox', { name: '金额值' });
  fireEvent.change(input, { target: { value: 'abc' } });
  expect(input.getAttribute('aria-invalid')).toBe('true');
  expect(input.getAttribute('aria-describedby')).toBe(
    screen.getByRole('alert').id,
  );
  fireEvent.change(input, { target: { value: '12' } });
  expect(input.getAttribute('aria-invalid')).not.toBe('true');
  expect(input.getAttribute('aria-describedby')).toBeNull();
});

it('also associates relative-day validation with the actual days input', () => {
  render(
    <FilterPanel
      fields={[{ field: 'created', label: '创建', type: 'datetime' }]}
      defaultValue={configuration(node('RECENT_DAYS', 'created', { days: 2 }))}
      onApply={() => {}}
    />,
  );
  const input = screen.getByRole('textbox', { name: /天数/ });
  fireEvent.change(input, { target: { value: 'abc' } });
  expect(input.getAttribute('aria-invalid')).toBe('true');
  expect(input.getAttribute('aria-describedby')).toBe(
    screen.getByRole('alert').id,
  );
});
