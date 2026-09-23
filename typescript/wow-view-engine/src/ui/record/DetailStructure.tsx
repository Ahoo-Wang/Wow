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

import type * as React from 'react';
import { cn } from 'cn';
import type { RecordData } from '../../model/index.js';
import { recordValue } from '../../record/index.js';
import { CopyButton } from '../CopyButton.js';
import {
  heldReading,
  type DisplayContext,
  type DisplayField,
} from '../display.js';
import type { MessageFormatters } from '../MessagesProvider.js';
import { LongText, ToneBadge } from '../variants.js';

/** One value read the way the detail reads a declared field. */
export type ReadField = (
  value: unknown,
  field: DisplayField,
) => React.ReactNode;

/**
 * How deep a structure is laid out before the rest of it is written whole.
 * A document nests as deep as its author made it; past this, key under key
 * stops being easier to read than the text of it.
 */
const DEPTH = 6;

/** A value the detail lays out as a structure rather than as one reading. */
export function isStructure(value: unknown): value is object {
  if (!isObject(value)) return false;
  return !Array.isArray(value) || value.some(isObject);
}

/**
 * A structured value in a record's detail, read whole — which is what the
 * detail is for.
 *
 * A cell reads an array of objects by its elements' titles and an object by
 * how many fields it holds, because a row is one line (`heldReading`). The
 * detail has the room, and what a reader opened it for is often exactly
 * what those readings leave out: the event a failure recorded, its payload,
 * the stack trace inside it. So an array of objects is laid out element by
 * element — the element's title, then each field the definition declares
 * for an element in that field's own reading, then whatever the element
 * holds that nothing declares — and an object with no declared elements is
 * laid out key by key. What nothing declares has no label to show, so its
 * keys are shown as they are written, in the monospace a key is.
 */
export function DetailStructure({
  value,
  field,
  read,
  messages,
  display,
}: {
  value: object;
  field: DisplayField;
  read: ReadField;
  messages: MessageFormatters;
  display: DisplayContext;
}) {
  const declared = field.elements !== undefined || field.elementTitle;
  if (!Array.isArray(value))
    return declared ? (
      <Element element={value} field={field} read={read} messages={messages} />
    ) : (
      <Tree value={value} depth={1} messages={messages} />
    );
  return (
    <ol data-slot="detail-elements" className="flex flex-col gap-3">
      {value.map((element, index) => (
        <li
          key={index}
          data-slot="detail-element"
          className="flex flex-col gap-1.5 border-l-2 pl-3"
        >
          <span className="flex flex-wrap items-center gap-2">
            <span className="text-muted-foreground text-xs tabular-nums">
              {messages.label('label.record.detail.element', {
                index: index + 1,
              })}
            </span>
            <ElementTitle
              element={element}
              field={field}
              messages={messages}
              display={display}
            />
          </span>
          <Element
            element={element}
            field={field}
            read={read}
            messages={messages}
          />
        </li>
      ))}
    </ol>
  );
}

/** The element's title badge, in the tone its option declares. */
function ElementTitle({
  element,
  field,
  messages,
  display,
}: {
  element: unknown;
  field: DisplayField;
  messages: MessageFormatters;
  display: DisplayContext;
}) {
  if (!field.elementTitle || !isObject(element)) return null;
  const held = heldReading(element, field, messages, display);
  const entry = held && 'elements' in held ? held.elements[0] : undefined;
  if (!entry) return null;
  return <ToneBadge tone={entry.tone ?? 'neutral'}>{entry.label}</ToneBadge>;
}

/**
 * One element's fields: the declared ones by their labels and readings, the
 * title left out since it heads the element; then what nothing declares.
 */
function Element({
  element,
  field,
  read,
  messages,
}: {
  element: unknown;
  field: DisplayField;
  read: ReadField;
  messages: MessageFormatters;
}) {
  if (!isObject(element) || Array.isArray(element))
    return <Tree value={element} depth={1} messages={messages} />;
  const declared = field.elements ?? [];
  const names = new Set(declared.map(one => one.field));
  const title = field.elementTitle?.name;
  const shown = declared.filter(
    one =>
      one.field !== title &&
      recordValue(element as RecordData, one.field) !== undefined,
  );
  const rest = Object.entries(element).filter(
    ([key, value]) => !names.has(key) && key !== title && value !== undefined,
  );
  if (shown.length === 0 && rest.length === 0) return null;
  return (
    <dl className={PAIRS}>
      {shown.map(one => {
        const value = recordValue(element as RecordData, one.field);
        const block = blockOf(value);
        return (
          <div key={one.field} className="contents">
            <dt className={cn('text-muted-foreground', block && 'col-span-2')}>
              {one.label}
            </dt>
            <dd
              className={cn(VALUE, block)}
              data-block={block ? '' : undefined}
            >
              {read(value, one) ?? '—'}
            </dd>
          </div>
        );
      })}
      {rest.map(([key, value]) => (
        <Pair
          key={key}
          name={key}
          value={value}
          depth={2}
          messages={messages}
        />
      ))}
    </dl>
  );
}

/** A value nothing declares, laid out by what it is. */
function Tree({
  value,
  depth,
  messages,
}: {
  value: unknown;
  depth: number;
  messages: MessageFormatters;
}): React.ReactNode {
  if (value === null || value === undefined) return '—';
  if (typeof value === 'string')
    return isLong(value) ? <LongValue value={value} /> : value;
  if (!isObject(value)) return scalarText(value);
  if (depth > DEPTH)
    return <LongValue value={JSON.stringify(value, null, 2)} />;
  if (Array.isArray(value)) {
    if (value.length === 0) return '—';
    if (!value.some(isObject))
      return value
        .map(item => (typeof item === 'string' ? item : scalarText(item)))
        .join(messages.label('label.filter.join'));
    return (
      <ol className="flex flex-col gap-2">
        {value.map((item, index) => (
          <li key={index} className="border-l-2 pl-3">
            <Tree value={item} depth={depth + 1} messages={messages} />
          </li>
        ))}
      </ol>
    );
  }
  const entries = Object.entries(value).filter(([, one]) => one !== undefined);
  if (entries.length === 0) return '—';
  return (
    <dl className={PAIRS}>
      {entries.map(([key, one]) => (
        <Pair
          key={key}
          name={key}
          value={one}
          depth={depth + 1}
          messages={messages}
        />
      ))}
    </dl>
  );
}

/** One key nothing declares, written as it is, and its value. */
function Pair({
  name,
  value,
  depth,
  messages,
}: {
  name: string;
  value: unknown;
  depth: number;
  messages: MessageFormatters;
}) {
  const block = blockOf(value);
  return (
    <div className="contents">
      <dt
        className={cn(
          'text-muted-foreground font-mono text-xs leading-5',
          block && 'col-span-2',
        )}
      >
        {name}
      </dt>
      <dd className={cn(VALUE, block)} data-block={block ? '' : undefined}>
        <Tree value={value} depth={depth} messages={messages} />
      </dd>
    </div>
  );
}

/**
 * Where a value sits against its name: beside it, or — for a structure or
 * text longer than a line — under it, across the whole width. Beside its
 * name, each level of a document would give up the name column's width
 * again, and a stack trace three levels down was a column a few characters
 * wide. Under it, a level costs one rule's indent; a long text is its own
 * block already and takes no rule.
 *
 * `undefined` is beside; otherwise the classes the value's `<dd>` takes,
 * and its `<dt>` then takes `col-span-2`.
 */
export function blockOf(value: unknown): string | undefined {
  if (typeof value === 'string')
    return isLong(value) ? 'col-span-2' : undefined;
  if (!isObject(value) || (Array.isArray(value) && !value.some(isObject)))
    return undefined;
  return 'col-span-2 border-l-2 pl-3';
}

/**
 * A long value read whole: kept as written, in a block of its own that
 * scrolls, with the means to take it away — an error message, a stack
 * trace.
 */
export function LongValue({ value }: { value: string }) {
  return (
    <span data-slot="cell-long" className="group/copyable relative block">
      <LongText>{value}</LongText>
      <CopyButton value={value} className="absolute top-1 right-1" />
    </span>
  );
}

/** Past this many characters a value is read as a paragraph, not a label. */
const LONG_VALUE = 120;

export function isLong(value: string): boolean {
  return value.length > LONG_VALUE || value.includes('\n');
}

const PAIRS =
  'grid grid-cols-[minmax(4rem,max-content)_minmax(0,1fr)] gap-x-3 gap-y-1';
const VALUE = 'min-w-0 [overflow-wrap:anywhere]';

/** A number, a flag or nothing, as the document wrote it. */
function scalarText(value: unknown): string {
  if (
    typeof value === 'number' ||
    typeof value === 'boolean' ||
    typeof value === 'bigint'
  )
    return String(value);
  return '—';
}

function isObject(value: unknown): value is object {
  return typeof value === 'object' && value !== null;
}
