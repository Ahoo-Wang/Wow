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

import { useRef, useState, type ReactNode, type RefObject } from 'react';
import { EllipsisVerticalIcon } from 'lucide-react';
import {
  DropdownMenu,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '../components/dropdown-menu.js';
import { IconButton } from '../IconButton.js';
import { useViewMessages } from '../MessagesProvider.js';
import { DropdownMenuContent } from '../popups.js';
import { PillInput } from '../variants.js';

/**
 * A tray card's name, and the way to change it (D20 显示名). At rest it is
 * the word the card is known by — the name the analyst gave, else what the
 * field and the summary compose; while renaming it is a box holding the
 * given name, committed by Enter or by leaving it, dropped by Escape. An
 * emptied box takes the name back rather than storing a blank. When the box
 * goes, focus goes back to the menu it was opened from rather than to the
 * page.
 */
export function CardName({
  name,
  given,
  renaming,
  label,
  onRename,
  onDone,
}: CardNameProps & { renaming: boolean }) {
  if (!renaming)
    return (
      // `truncate` cuts the name off at the card's width, and the whole of
      // it is then nowhere: a pointer has no way to read what was cut. The
      // reader already hears it — the text node is intact — so `title` is
      // what the pointer is owed.
      <span
        data-slot="card-name"
        className="truncate font-medium"
        title={given ?? name}
      >
        {given ?? name}
      </span>
    );
  // Mounted only while it is open, so every edit starts from the name that
  // is stored rather than from whatever the last abandoned one typed.
  return (
    <NameBox
      name={name}
      given={given}
      label={label}
      onRename={onRename}
      onDone={onDone}
    />
  );
}

interface CardNameProps {
  /** What the card is called when the analyst has named nothing. */
  name: string;
  /** The name the analyst gave, if any: what the box starts with. */
  given: string | undefined;
  /** The box's accessible name. */
  label: string;
  onRename(label: string | undefined): void;
  onDone(): void;
}

function NameBox({ name, given, label, onRename, onDone }: CardNameProps) {
  const [text, setText] = useState(given ?? '');
  /**
   * Leaving the box is what hands focus back to the menu, and a focus move
   * is a blur — so a box that committed on blur committed the very text
   * Escape had just thrown away. It settles once, whichever way it was
   * left, and the blur that follows has nothing left to do.
   */
  const settled = useRef(false);
  const leave = (keep: boolean) => {
    if (settled.current) return;
    settled.current = true;
    if (keep) {
      const trimmed = text.trim();
      onRename(trimmed === '' ? undefined : trimmed);
    }
    onDone();
  };
  return (
    <PillInput
      aria-label={label}
      chrome="box"
      className="w-32"
      autoFocus
      value={text}
      placeholder={name}
      onChange={event => setText(event.target.value)}
      onBlur={() => leave(true)}
      onKeyDown={event => {
        if (event.key === 'Enter') {
          event.preventDefault();
          leave(true);
        } else if (event.key === 'Escape') {
          event.preventDefault();
          leave(false);
        }
      }}
    />
  );
}

/**
 * The card's own menu: the settings a card has beyond its two or three
 * controls — a display name for every card, and for a dimension the
 * choices Wow keeps behind its bucketing. Its trigger is the last thing on
 * the card before the remove button, named after the card.
 */
export function CardMenu({
  name,
  disabled,
  onRename,
  children,
  ref,
}: {
  name: string;
  disabled?: boolean;
  /** Starts renaming the card. */
  onRename(): void;
  /** The dimension-only items, after the rename item. */
  children?: ReactNode;
  /** The trigger, so the card can hand focus back to it after a rename. */
  ref?: RefObject<HTMLButtonElement | null>;
}) {
  const messages = useViewMessages();
  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        render={
          <IconButton
            ref={ref}
            label={messages.label('label.analysis.card-menu', { name })}
            variant="ghost"
            size="icon-xs"
            className="ml-auto"
            data-slot="card-menu"
            disabled={disabled}
          />
        }
      >
        <EllipsisVerticalIcon />
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="min-w-44">
        <DropdownMenuGroup>
          <DropdownMenuItem onClick={onRename}>
            {messages.label('label.analysis.rename')}
          </DropdownMenuItem>
          {children}
        </DropdownMenuGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
