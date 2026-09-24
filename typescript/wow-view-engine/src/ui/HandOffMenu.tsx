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
  createContext,
  useContext,
  useRef,
  type ComponentProps,
  type RefObject,
} from 'react';
import { DropdownMenu, DropdownMenuItem } from './components/dropdown-menu.js';
import { DropdownMenuContent } from './popups.js';

/**
 * Whether the item last chosen handed the keyboard to what it opened, for
 * the menu it closes to read. `null` outside a `HandOffMenu`.
 */
const HandedOff = createContext<RefObject<boolean> | null>(null);

/**
 * A menu some of whose items open something that takes the keyboard — a
 * dialog, a box to type a name in (U-01).
 *
 * A menu gives the keyboard back to its trigger as it closes, and it closes
 * *after* the item's dialog has opened: its exit animation runs on for a
 * moment, then its focus manager puts the keyboard on the trigger — behind
 * the dialog, which is modal, on the board it covers. What was typed went
 * to the board. So an item that opens something says so
 * (`DialogMenuItem`), and the menu then leaves the keyboard where it went;
 * every other item, and Escape, still hand it back to the trigger.
 *
 * `HandOffMenu` is the menu's root, `HandOffMenuContent` its popup, and the
 * two read one flag, reset each time the menu opens.
 */
export function HandOffMenu({
  onOpenChange,
  ...props
}: ComponentProps<typeof DropdownMenu>) {
  const handedOff = useRef(false);
  return (
    <HandedOff.Provider value={handedOff}>
      <DropdownMenu
        {...props}
        onOpenChange={(open, details) => {
          if (open) handedOff.current = false;
          onOpenChange?.(open, details);
        }}
      />
    </HandedOff.Provider>
  );
}

/** The popup of a `HandOffMenu`: back to the trigger unless an item handed off. */
export function HandOffMenuContent(
  props: Omit<ComponentProps<typeof DropdownMenuContent>, 'finalFocus'>,
) {
  const handedOffRef = useContext(HandedOff);
  return (
    <DropdownMenuContent {...props} finalFocus={() => !handedOffRef?.current} />
  );
}

/**
 * A menu item that opens a dialog, or anything else that takes the
 * keyboard as it appears: the menu closing after it leaves the keyboard
 * there. Whatever it opened hands the keyboard back when it closes, to the
 * control it names (`FinalFocus`).
 */
export function DialogMenuItem({
  onClick,
  ...props
}: ComponentProps<typeof DropdownMenuItem>) {
  const handedOffRef = useContext(HandedOff);
  return (
    <DropdownMenuItem
      {...props}
      onClick={event => {
        if (handedOffRef) handedOffRef.current = true;
        onClick?.(event);
      }}
    />
  );
}
