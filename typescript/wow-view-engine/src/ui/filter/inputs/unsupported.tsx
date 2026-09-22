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

import { cn } from 'cn';
import type { FieldKindId } from '../../../model/index.js';
import { useViewMessages } from '../../MessagesProvider.js';

/**
 * A value no control can hold: what the config stores, and why nothing here
 * can edit it.
 *
 * Two situations arrive at the same place, and from where the user sits they
 * are one situation. A field's kind may be missing from the
 * `FieldKindRegistry` — a definition shipped one release and a saved view
 * still asks by it — and then there are no operators to offer and no value
 * shape to draw. Or a registered kind may ask for an `EditorDescriptor.input`
 * outside the closed union, which is the same absence one step further in.
 * Either way the honest answer is the stored value, said once, with the
 * reason beside it; the text box that used to stand here invited an edit that
 * would have written a shape nobody could read back.
 *
 * It is read-only and not `disabled`: a disabled control is one that could be
 * used later, and this one could not. `validateFilter` refuses the condition
 * in the same breath (`filter.kind.unregistered`, `filter.kind.unknown-editor`)
 * so Apply does not run a tree the kernel cannot compile, and the pill around
 * this wears the error border every other refused condition wears.
 *
 * An `Alert` is what the shadcn rules would draw for a reason like this, and
 * it is declined here on purpose: this sits inside a condition pill, which is
 * already a bordered block in the destructive tone, and an alert inside it
 * would be a second frame and a second border around one sentence.
 */
export function UnsupportedValue({
  kind,
  value,
  className,
}: {
  /** The kind whose editor is missing; the sentence names it. */
  kind: FieldKindId;
  value: unknown;
  className?: string;
}) {
  const messages = useViewMessages();
  const text = rawValueText(value);
  return (
    <div
      data-slot="filter-unsupported"
      className={cn('flex min-w-0 flex-col gap-0.5 py-1', className)}
    >
      {text !== '' && (
        // The value is shown, not offered: it is what the view asks for and
        // the user is entitled to read it before deciding to remove the row.
        <span className="truncate">{text}</span>
      )}
      <span className="text-destructive text-xs">
        {messages.label('label.filter.kind-unregistered', { kind })}
      </span>
    </div>
  );
}

/**
 * A stored value as text, with nothing read into it.
 *
 * Every other reading of a value in this package goes through the field's
 * kind — `displayValue` for a cell, `describeFilter` for the applied bar —
 * and the whole point here is that there is no kind to ask. So this prints
 * what the config holds and invents nothing: a string as itself, a number or
 * a boolean as it reads, anything else as its JSON. A config arrives from a
 * store and may hold a cycle, which `JSON.stringify` throws on, so a value
 * that cannot even be printed reads as no value at all rather than as a
 * crashed panel.
 */
function rawValueText(value: unknown): string {
  if (value === null || value === undefined) return '';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean')
    return String(value);
  try {
    return JSON.stringify(value) ?? '';
  } catch {
    return '';
  }
}
