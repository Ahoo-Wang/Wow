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

import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { BooleanValue } from '../src/ui/filter/inputs/select.js';
import { ViewSurface } from '../src/ui/index.js';

afterEach(cleanup);

describe('a yes-or-no condition’s two answers', () => {
  async function answers(options?: { value: string; label: string }[]) {
    const onChange = vi.fn();
    render(
      <ViewSurface>
        <BooleanValue
          value={null}
          onChange={onChange}
          label="New buyer"
          {...(options ? { options } : {})}
        />
      </ViewSurface>,
    );
    await userEvent.click(screen.getByRole('combobox', { name: 'New buyer' }));
    const found = await screen.findAllByRole('option');
    return { names: found.map(option => option.textContent), found, onChange };
  }

  it('are the field’s own words where it names them, and still true and false', async () => {
    const { names, found, onChange } = await answers([
      { value: 'true', label: 'New' },
      { value: 'false', label: 'Returning' },
    ]);
    expect(names).toEqual(['New', 'Returning']);
    await userEvent.click(found[1]!);
    expect(onChange).toHaveBeenCalledWith(false);
  });

  it('are the catalogue’s where it does not', async () => {
    const { names } = await answers();
    expect(names).toEqual(['Yes', 'No']);
  });
});
