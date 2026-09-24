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

import type { MouseEventHandler, ReactElement } from 'react';
import { PlusIcon } from 'lucide-react';
import type { ViewKind } from '../../model/index.js';
import {
  DropdownMenu,
  DropdownMenuItem,
  DropdownMenuSub,
  DropdownMenuSubTrigger,
  DropdownMenuTrigger,
} from '../components/dropdown-menu.js';
import { KIND_ICON, useKindWord } from '../kinds.js';
import { useViewMessages } from '../MessagesProvider.js';
import { DropdownMenuContent, DropdownMenuSubContent } from '../popups.js';

/**
 * The one command behind every "new view" control: which kinds a view may
 * be made of here, and the making. `creatable` is the whole of whether the
 * control exists (D4): none, and the control is not drawn.
 */
export interface NewViewCommand {
  creatable: readonly ViewKind[];
  create(kind: ViewKind): void;
}

/**
 * A control that makes a view, in whichever form its place needs.
 *
 * One creatable kind is the trigger itself: pressing it makes that kind, and
 * the control reads "New view" as it always did. Several are a menu of the
 * kinds (D20 Ⅱ): the kind decides which kernel the view runs on, so it is
 * asked before the view opens rather than switched inside it — and the
 * trigger then opens the menu. The trigger is the caller's, because the
 * sidebar wants an icon button and the empty work area a labelled one.
 */
export function NewViewControl({
  command,
  trigger,
}: {
  command: NewViewCommand;
  /**
   * The button as the caller draws it. With one kind it is handed the press
   * that makes it; with several it is handed nothing and becomes the menu's
   * trigger, which presses it open.
   */
  trigger(props: { onClick?: MouseEventHandler }): ReactElement;
}) {
  const only = command.creatable.length === 1 ? command.creatable[0] : null;
  if (only !== undefined && only !== null)
    return trigger({ onClick: () => command.create(only) });
  return (
    <DropdownMenu>
      <DropdownMenuTrigger render={trigger({})} />
      <DropdownMenuContent>
        <NewViewKinds command={command} />
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

/**
 * The same command inside a menu that is already open — the switcher's: one
 * kind is one item, several are a submenu of the kinds.
 */
export function NewViewItem({ command }: { command: NewViewCommand }) {
  const messages = useViewMessages();
  const word = useKindWord();
  const only = command.creatable.length === 1 ? command.creatable[0] : null;
  if (only !== undefined && only !== null)
    return (
      <DropdownMenuItem onClick={() => command.create(only)}>
        <PlusIcon />
        {messages.label(word('label.view.new'))}
      </DropdownMenuItem>
    );
  return (
    <DropdownMenuSub>
      <DropdownMenuSubTrigger>
        <PlusIcon />
        {messages.label(word('label.view.new'))}
      </DropdownMenuSubTrigger>
      <DropdownMenuSubContent>
        <NewViewKinds command={command} />
      </DropdownMenuSubContent>
    </DropdownMenuSub>
  );
}

/** One item per kind, in the workbench's order, each wearing its icon. */
function NewViewKinds({ command }: { command: NewViewCommand }) {
  const messages = useViewMessages();
  return command.creatable.map(kind => {
    const Kind = KIND_ICON[kind];
    return (
      <DropdownMenuItem key={kind} onClick={() => command.create(kind)}>
        <Kind />
        {messages.label(`label.kind.${kind}`)}
      </DropdownMenuItem>
    );
  });
}
