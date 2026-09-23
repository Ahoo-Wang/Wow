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
import { useState } from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import type {
  FilterValue,
  ValueCandidateSource,
  ValueCandidates,
} from '../src/index.js';
import { FilterValueEditor, ViewSurface, zhCN } from '../src/ui/index.js';

afterEach(cleanup);

function last(changes: FilterValue[]): FilterValue {
  return changes[changes.length - 1];
}

interface Call {
  query: string;
  signal?: AbortSignal;
}

function fakeSource(answers: Record<string, ValueCandidates | Error>): {
  source: ValueCandidateSource;
  calls: Call[];
} {
  const calls: Call[] = [];
  return {
    calls,
    source: {
      field: 'processor',
      search(query, signal) {
        calls.push({ query, signal });
        const answer = answers[query] ?? { values: [], complete: true };
        return answer instanceof Error
          ? Promise.reject(answer)
          : Promise.resolve(answer);
      },
    },
  };
}

const PROCESSORS: ValueCandidates = {
  values: [
    { value: 'QuotationSaga', count: 615004 },
    { value: 'OrderSaga', count: 26939 },
    { value: 'PaymentProcessor', count: 12 },
  ],
  complete: true,
};

function editor(
  source: ValueCandidateSource,
  {
    multiple = false,
    initial = (multiple ? [] : '') as FilterValue,
    zh = false,
  } = {},
) {
  const changes: FilterValue[] = [];
  function Host() {
    const [value, setValue] = useState<FilterValue>(initial);
    return (
      <ViewSurface
        {...(zh ? { locale: 'zh-CN', messages: zhCN } : { locale: 'en-US' })}
      >
        <FilterValueEditor
          kind="string"
          editor={{ input: 'text', multiple }}
          value={value}
          label="processor"
          candidates={source}
          onChange={next => {
            changes.push(next);
            setValue(next);
          }}
        />
      </ViewSurface>
    );
  }
  render(<Host />);
  return { changes };
}

describe('a text value offered from the data', () => {
  it('lists the values with how many records hold each, and writes the one picked', async () => {
    const user = userEvent.setup();
    const { source, calls } = fakeSource({ '': PROCESSORS });
    const { changes } = editor(source);
    // A pill merely on screen asks nothing.
    expect(calls).toEqual([]);

    const box = screen.getByRole('combobox', { name: 'processor' });
    expect(box.getAttribute('placeholder')).toBe('Pick or type a value');
    await user.click(box);
    const option = await screen.findByRole('option', {
      name: 'QuotationSaga (615,004 records)',
    });
    // The count as the surface prints every number.
    expect(
      option.querySelector('[data-slot="candidate-count"]')?.textContent,
    ).toBe('615,004');
    expect(calls.map(call => call.query)).toEqual(['']);
    // The list is named for the value it offers, as the box is.
    expect(screen.getByRole('listbox', { name: 'processor' })).toBeDefined();

    await user.click(screen.getByRole('option', { name: /^OrderSaga/ }));
    expect(last(changes)).toBe('OrderSaga');
  });

  it('keeps what is typed as the value, whether or not the list holds it', async () => {
    const user = userEvent.setup();
    const { source } = fakeSource({ '': PROCESSORS });
    const { changes } = editor(source);
    const box = screen.getByRole('combobox', { name: 'processor' });
    await user.click(box);
    await screen.findByRole('option', { name: /^QuotationSaga/ });

    await user.type(box, 'saga');
    // What is listed is narrowed at once; nothing the text left stays.
    await waitFor(() =>
      expect(
        screen.queryByRole('option', { name: /^PaymentProcessor/ }),
      ).toBeNull(),
    );
    expect(screen.getByRole('option', { name: /^OrderSaga/ })).toBeDefined();
    expect(last(changes)).toBe('saga');

    await user.clear(box);
    await user.type(box, 'RareSaga');
    expect(last(changes)).toBe('RareSaga');
    await screen.findByText('No match');
  });

  it('adds several values, one of them typed', async () => {
    const user = userEvent.setup();
    const { source } = fakeSource({ '': PROCESSORS });
    const { changes } = editor(source, { multiple: true });
    const box = screen.getByRole('combobox', { name: 'processor' });
    await user.click(box);
    await user.click(
      await screen.findByRole('option', { name: /^QuotationSaga/ }),
    );
    expect(last(changes)).toEqual(['QuotationSaga']);

    await user.type(box, 'RareSaga');
    // After the field's values, the text itself, to be added as it is.
    await screen.findByRole('option', { name: 'Add RareSaga' });
    await user.keyboard('{Escape}');
    await user.keyboard('{Enter}');
    expect(last(changes)).toEqual(['QuotationSaga', 'RareSaga']);
    expect(
      screen.getByRole('button', { name: 'Remove RareSaga' }),
    ).toBeDefined();

    // Leaving the box adds what was typed, too.
    await user.type(box, 'Other');
    await user.tab();
    expect(last(changes)).toEqual(['QuotationSaga', 'RareSaga', 'Other']);
  });

  it('says when only the most frequent values are listed', async () => {
    const user = userEvent.setup();
    const { source } = fakeSource({
      '': { ...PROCESSORS, complete: false },
    });
    editor(source);
    await user.click(screen.getByRole('combobox', { name: 'processor' }));
    await screen.findByText(
      'Only the most frequent values are listed; type to narrow',
    );
  });

  it("says why the values could not be read in the source's words, and asks again", async () => {
    const user = userEvent.setup();
    const { source, calls } = fakeSource({ '': new Error('HTTP 503') });
    editor(source);
    await user.click(screen.getByRole('combobox', { name: 'processor' }));
    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain(
      'The values could not be read: HTTP 503',
    );
    expect(screen.queryByText('No match')).toBeNull();

    await user.click(within(alert).getByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(calls).toHaveLength(2));
  });

  it('speaks the surface language, counts included', async () => {
    const user = userEvent.setup();
    const { source } = fakeSource({ '': PROCESSORS });
    editor(source, { zh: true });
    const box = screen.getByRole('combobox', { name: 'processor' });
    expect(box.getAttribute('placeholder')).toBe('选择或输入一个值');
    await user.click(box);
    await screen.findByRole('option', {
      name: 'QuotationSaga（615,004 条记录）',
    });
  });
});
