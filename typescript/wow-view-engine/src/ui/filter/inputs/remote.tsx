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

import { useState } from 'react';
import { XIcon } from 'lucide-react';
import { Combobox as ComboboxPrimitive } from '@base-ui/react';
import type { FieldOption } from '../../../model/index.js';
import {
  isReferenceFilterValue,
  type ReferenceItem,
} from '../../../filter/index.js';
import type { OptionSource } from '../../../runtime/index.js';
import { Button } from '../../components/button.js';
import {
  Combobox,
  ComboboxChip,
  ComboboxChipsInput,
  ComboboxEmpty,
  ComboboxInput,
  ComboboxItem,
  ComboboxList,
  ComboboxValue,
  useComboboxAnchor,
} from '../../components/combobox.js';
import { useViewMessages } from '../../MessagesProvider.js';
import { ComboboxContent } from '../../popups.js';
import { PillChips } from '../../variants.js';
import { useCandidates } from './candidates.js';
import type { ValueProps } from './shared.js';
import { TextValue } from './text.js';

/**
 * A value the host looks up rather than the kind declaring it.
 *
 * Three ways in, by what the host wired: the whole list at once (`options`),
 * a searchable source (`source`, from `resolveOptions`), or neither — then
 * the field is typed, since nothing is lost by typing an id that is already
 * valid. The value keeps whatever shape it arrived in: a `reference` kind's
 * `{ items: [{ id, label }] }`, where the label rides with the id so a saved
 * view reopens without asking the source again, or a plain id list for a
 * host kind that stores only ids.
 */
export function RemoteValue({
  value,
  onChange,
  label,
  disabled,
  invalid,
  multiple,
  options,
  source,
}: ValueProps & {
  multiple: boolean;
  options?: FieldOption[];
  source?: OptionSource | null;
}) {
  if (!options && !source)
    return (
      <TextValue
        value={value}
        onChange={onChange}
        label={label}
        disabled={disabled}
        invalid={invalid}
        multiple={multiple}
      />
    );
  return (
    <CandidateValue
      value={value}
      onChange={onChange}
      label={label}
      disabled={disabled}
      invalid={invalid}
      multiple={multiple}
      options={options}
      source={source ?? null}
    />
  );
}

/** One candidate as the control holds it: the reference kind's own shape. */
const toItem = (option: FieldOption): ReferenceItem => ({
  id: option.value,
  label: option.label,
});

const sameItem = (a: ReferenceItem, b: ReferenceItem) => a.id === b.id;

/** The ids a plain (non-reference) value holds. */
function idsOf(value: unknown): (string | number)[] {
  const list = Array.isArray(value) ? value : [value];
  return list.filter(
    (entry): entry is string | number =>
      typeof entry === 'string' || typeof entry === 'number',
  );
}

function CandidateValue({
  value,
  onChange,
  label,
  disabled,
  invalid,
  multiple,
  options,
  source,
}: ValueProps & {
  multiple: boolean;
  options?: FieldOption[];
  source: OptionSource | null;
}) {
  const messages = useViewMessages();
  const anchor = useComboboxAnchor();
  const [query, setQuery] = useState('');
  const [open, setOpen] = useState(false);
  // A host that handed over the whole list is not searched: the list is
  // filtered where it is, by label, and the source is left alone.
  const { state, more, retry } = useCandidates(
    options ? null : source,
    query,
    open,
  );

  const reference = isReferenceFilterValue(value);
  const listed: ReferenceItem[] = (options ?? state.items).map(toItem);
  const labelOf = (id: string | number): string =>
    listed.find(item => item.id === id)?.label ?? String(id);
  const selected: ReferenceItem[] = reference
    ? value.items
    : idsOf(value).map(id => ({ id, label: labelOf(id) }));

  // The value keeps its shape: labels travel with a reference, ids alone
  // with anything else.
  const write = (next: ReferenceItem[]) => {
    if (reference)
      onChange({
        items: next.map(item => ({ id: item.id, label: item.label })),
      });
    else if (multiple) onChange(next.map(item => item.id));
    else onChange(next[0]?.id ?? null);
  };

  const status =
    state.status === 'loading' && !state.loadingMore
      ? messages.label('label.filter.candidates-loading')
      : null;
  const footer = (
    <>
      {status !== null && (
        <div
          role="status"
          data-slot="candidate-status"
          className="text-muted-foreground px-2 py-1.5 text-xs"
        >
          {status}
        </div>
      )}
      {state.status === 'failed' && (
        <div
          role="alert"
          data-slot="candidate-status"
          data-failed=""
          className="text-destructive flex items-center justify-between gap-2 px-2 py-1.5 text-xs"
        >
          <span>{messages.label('label.filter.candidates-failed')}</span>
          <Button type="button" variant="ghost" size="xs" onClick={retry}>
            {messages.label('label.filter.candidates-retry')}
          </Button>
        </div>
      )}
      {state.status === 'success' && state.nextCursor !== null && (
        <div className="px-1 py-1">
          <Button
            type="button"
            variant="ghost"
            size="xs"
            className="w-full"
            disabled={state.loadingMore}
            onClick={more}
          >
            {messages.label('label.filter.more-candidates')}
          </Button>
        </div>
      )}
    </>
  );
  // «No match» is an answer, not a pause: it is said once a page has come
  // back empty, never while one is on its way or after one failed.
  const empty = (options !== undefined || state.status === 'success') && (
    <ComboboxEmpty>{messages.label('label.filter.no-candidate')}</ComboboxEmpty>
  );
  const list = (
    <ComboboxList>
      {(item: ReferenceItem) => (
        <ComboboxItem key={String(item.id)} value={item}>
          {item.label}
        </ComboboxItem>
      )}
    </ComboboxList>
  );

  if (!multiple)
    return (
      <Combobox
        items={listed}
        value={selected[0] ?? null}
        onValueChange={next => write(next ? [next] : [])}
        open={open}
        onOpenChange={setOpen}
        onInputValueChange={setQuery}
        filter={options ? undefined : null}
        itemToStringLabel={(item: ReferenceItem) => item.label}
        isItemEqualToValue={sameItem}
        disabled={disabled}
      >
        <ComboboxInput
          aria-label={label}
          aria-invalid={invalid}
          placeholder={messages.label('label.filter.search-candidates')}
          className="w-full"
        />
        <ComboboxContent>
          {empty}
          {list}
          {footer}
        </ComboboxContent>
      </Combobox>
    );

  return (
    <Combobox
      multiple
      items={listed}
      value={selected}
      onValueChange={write}
      open={open}
      onOpenChange={setOpen}
      onInputValueChange={setQuery}
      filter={options ? undefined : null}
      itemToStringLabel={(item: ReferenceItem) => item.label}
      isItemEqualToValue={sameItem}
      disabled={disabled}
    >
      <PillChips ref={anchor} aria-invalid={invalid} data-slot="filter-chips">
        <ComboboxValue>
          {(items: ReferenceItem[]) => (
            <>
              {items.map(item => (
                <ComboboxChip key={String(item.id)} showRemove={false}>
                  {item.label}
                  <ComboboxPrimitive.ChipRemove
                    render={<Button variant="ghost" size="icon-xs" />}
                    aria-label={messages.label('label.filter.remove-value', {
                      value: item.label,
                    })}
                    className="-ml-1 opacity-50 hover:opacity-100"
                    data-slot="combobox-chip-remove"
                  >
                    <XIcon className="pointer-events-none" />
                  </ComboboxPrimitive.ChipRemove>
                </ComboboxChip>
              ))}
              <ComboboxChipsInput
                aria-label={label}
                placeholder={
                  items.length === 0
                    ? messages.label('label.filter.search-candidates')
                    : undefined
                }
              />
            </>
          )}
        </ComboboxValue>
      </PillChips>
      <ComboboxContent anchor={anchor}>
        {empty}
        {list}
        {footer}
      </ComboboxContent>
    </Combobox>
  );
}
