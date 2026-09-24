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
  useRef,
  useState,
  type FocusEvent,
  type KeyboardEvent,
  type RefObject,
} from 'react';
import { CheckIcon, XIcon } from 'lucide-react';
import { Input } from './components/input.js';
import {
  InputGroup,
  InputGroupAddon,
  InputGroupButton,
  InputGroupInput,
} from './components/input-group.js';
import { IconTooltip } from './IconButton.js';

export interface RenameInputProps {
  /** What the name says now; the field opens holding it, selected. */
  initial: string;
  /** The field's accessible name — best after the thing it renames. */
  label: string;
  /** A new name, trimmed; never the one it opened with. */
  onCommit(name: string): void;
  /** The edit ended with the name as it was. */
  onCancel(): void;
  /**
   * A blank name is refused: Enter and ✓ do nothing, and leaving the field
   * puts the old name back. Left out, a blank name is a name like any other
   * — a panel's, which then goes back to being named after its view.
   */
  required?: boolean;
  /**
   * The name cannot be taken right now (a write is in flight): Enter and ✓
   * wait, and leaving the field leaves it open rather than dropping what
   * was typed.
   */
  held?: boolean;
  /** Where the keyboard goes when Enter or Escape ends the edit. */
  returnTo?: RefObject<HTMLElement | null>;
  /**
   * ✓ and ✕ drawn in the field, named — for a row where the field is the
   * only thing that says it is being edited.
   */
  answers?: { confirm: string; cancel: string };
  className?: string;
  'data-slot'?: string;
}

/**
 * A name typed in place — a panel's title, a tab's, a view's in the manager
 * (Q-10): one field, one set of rules.
 *
 * It appears focused with the name selected, so typing replaces it. Enter
 * keeps what was typed and Escape keeps what was there; both stop at the
 * field, since the field may sit in a dialog or a popover that the same key
 * would otherwise close. Leaving it — a press elsewhere, Tab — keeps what
 * was typed too, the way a name edited in place is kept everywhere else;
 * moving onto its own ✓ or ✕ is not leaving. The name is trimmed, and an
 * unchanged one is not a rename: nothing is written, and a panel named after
 * its view does not take that name as a title of its own.
 *
 * It takes the keyboard as it mounts, in the same commit as the menu item
 * that asked for it: a menu closing after that finds the keyboard outside
 * it and leaves it where it is (unlike a dialog, which takes the keyboard
 * a moment later — U-01), so the tab bar's plain menu item and the panel
 * menu's `DialogMenuItem` both hand over (test/dashboardExtensions.test.tsx
 * and the TabsBuilt story in a browser).
 */
export function RenameInput({
  initial,
  label,
  onCommit,
  onCancel,
  required = false,
  held = false,
  returnTo,
  answers,
  className,
  'data-slot': slot,
}: RenameInputProps) {
  const [value, setValue] = useState(initial);
  // Enter and Escape end the edit before the blur they cause arrives.
  const ended = useRef(false);
  const name = value.trim();
  const refused = held || (required && name.length === 0);

  const end = (keep: boolean) => {
    if (ended.current) return;
    ended.current = true;
    if (keep && name !== initial.trim()) onCommit(name);
    else onCancel();
  };
  const confirm = () => {
    if (!refused) end(true);
  };
  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key !== 'Enter' && event.key !== 'Escape') return;
    event.preventDefault();
    event.stopPropagation();
    if (event.key === 'Enter' && refused) return;
    end(event.key === 'Enter');
    returnTo?.current?.focus();
  };
  const onBlur = (event: FocusEvent<HTMLElement>) => {
    // Onto the field's own ✓ or ✕: still editing.
    if (event.currentTarget.contains(event.relatedTarget)) return;
    if (held) return;
    // A blank name the field refuses is left as the old one.
    end(!refused);
  };
  const field = {
    // Focused as it appears: whatever asked for it hands the keyboard over.
    autoFocus: true,
    onFocus: (event: FocusEvent<HTMLInputElement>) =>
      event.currentTarget.select(),
    'aria-label': label,
    value,
    onChange: (event: { target: { value: string } }) =>
      setValue(event.target.value),
    onKeyDown,
  };

  if (!answers)
    return (
      <Input
        {...field}
        data-slot={slot}
        onBlur={onBlur}
        className={className}
      />
    );
  // A press on ✓ or ✕ keeps the keyboard in the field: a pointer that
  // focused the button would blur the field first, and a browser that
  // focuses nothing on a press would say the field was left.
  const keepFocus = (event: { preventDefault(): void }) =>
    event.preventDefault();
  return (
    <InputGroup data-slot={slot} onBlur={onBlur} className={className}>
      <InputGroupInput {...field} />
      <InputGroupAddon align="inline-end">
        <IconTooltip
          label={answers.confirm}
          render={
            <InputGroupButton
              size="icon-xs"
              disabled={refused}
              onMouseDown={keepFocus}
              onClick={confirm}
            />
          }
        >
          <CheckIcon />
        </IconTooltip>
        <IconTooltip
          label={answers.cancel}
          render={
            <InputGroupButton
              size="icon-xs"
              onMouseDown={keepFocus}
              onClick={() => end(false)}
            />
          }
        >
          <XIcon />
        </IconTooltip>
      </InputGroupAddon>
    </InputGroup>
  );
}
