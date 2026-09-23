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

import { createContext, useContext } from 'react';
import type * as React from 'react';
import {
  ArrowDownIcon,
  ArrowLeftIcon,
  ArrowRightIcon,
  ArrowUpIcon,
  ChevronsDownUpIcon,
  ChevronsLeftRightIcon,
  ChevronsRightLeftIcon,
  ChevronsUpDownIcon,
  GripVerticalIcon,
  MoveIcon,
} from 'lucide-react';
import type { ArrangeStep } from '../dashboard/index.js';
import { IconButton, IconTooltip } from './IconButton.js';
import type { MessageKey } from './messages.js';
import { useViewMessages } from './MessagesProvider.js';
import { Button } from './components/button.js';
import {
  DropdownMenu,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from './components/dropdown-menu.js';
import { DropdownMenuContent } from './popups.js';

/*
 * Placing a panel without a pointer.
 *
 * `react-grid-layout` 2.2 has no keyboard sensor — the drag is
 * `react-draggable`'s, which listens for mouse and touch and nothing else,
 * and the resize handle is `react-resizable`'s, the same. So the keyboard
 * equivalent is not a setting to turn on; it is a set of commands of our
 * own, over the one thing a gesture produces: a new `PanelLayout`.
 *
 * What a command lands on is the kernel's (`arrangePanel`, and `placePanel`
 * for the panels it then covers), the same rules a pointer's drop goes
 * through, so the keyboard and the pointer cannot place differently.
 *
 * Both handles answer the arrow keys for what that handle does by pointer:
 * the grip moves, the south-east corner resizes. The menu says the same
 * eight commands in words, because a key that is only discoverable by
 * pressing it is not discoverable.
 */

/** What each arrow key means on the grip, and on the resize corner. */
const MOVE_KEYS: Readonly<Record<string, ArrangeStep>> = {
  ArrowLeft: 'left',
  ArrowRight: 'right',
  ArrowUp: 'up',
  ArrowDown: 'down',
};

const SIZE_KEYS: Readonly<Record<string, ArrangeStep>> = {
  ArrowLeft: 'narrower',
  ArrowRight: 'wider',
  ArrowUp: 'shorter',
  ArrowDown: 'taller',
};

/**
 * The keys both handles answer, said on the element. The name says what the
 * control is for (「移动『北区订单』」); which keys work it is this
 * attribute's to say, and the menu beside the grip says the same commands in
 * words.
 */
const ARROW_KEYS = 'ArrowUp ArrowDown ArrowLeft ArrowRight';

/** The commands the menu lists, in two groups, each with its icon. */
const MOVE_COMMANDS: readonly (readonly [ArrangeStep, MessageKey, React.FC])[] =
  [
    ['up', 'label.panel.move-up', ArrowUpIcon],
    ['down', 'label.panel.move-down', ArrowDownIcon],
    ['left', 'label.panel.move-left', ArrowLeftIcon],
    ['right', 'label.panel.move-right', ArrowRightIcon],
  ];

const SIZE_COMMANDS: readonly (readonly [ArrangeStep, MessageKey, React.FC])[] =
  [
    ['wider', 'label.panel.wider', ChevronsLeftRightIcon],
    ['narrower', 'label.panel.narrower', ChevronsRightLeftIcon],
    ['taller', 'label.panel.taller', ChevronsUpDownIcon],
    ['shorter', 'label.panel.shorter', ChevronsDownUpIcon],
  ];

export interface PanelGripProps {
  /** What the panel is called, so every control here says which one it is. */
  title: string;
  onStep(step: ArrangeStep): void;
}

/**
 * The panel's drag handle, and the keys that do the same thing.
 *
 * It carries its name now. It used to be `aria-hidden` with a `title`,
 * because announcing a control a keyboard cannot work is worse than saying
 * nothing — but it answers the arrows, so it is a control like any other.
 * `data-slot="panel-grip"` is what the grid's `dragConfig.handle` selects,
 * so the pointer gesture is unchanged.
 */
export function PanelGrip({ title, onStep }: PanelGripProps) {
  const messages = useViewMessages();
  return (
    <IconButton
      type="button"
      data-slot="panel-grip"
      label={messages.label('label.panel.move', { title })}
      aria-keyshortcuts={ARROW_KEYS}
      variant="ghost"
      size="icon-sm"
      className="shrink-0 cursor-move"
      onKeyDown={(event: React.KeyboardEvent) => {
        const step = MOVE_KEYS[event.key];
        if (!step) return;
        // The arrows belong to the handle while it has the focus, whether
        // or not the grid has room for this one — otherwise pressing left
        // against the first column scrolls the page instead.
        event.preventDefault();
        onStep(step);
      }}
    >
      <GripVerticalIcon />
    </IconButton>
  );
}

/** Which panel a grid item holds, for the corner the library appends to it. */
interface PanelItemIdentity {
  id: string;
  /** What the panel is called on screen — never its id. */
  name: string;
}

const PanelItemContext = createContext<PanelItemIdentity | null>(null);

export interface PanelGridItemProps extends React.ComponentProps<'div'> {
  panelId: string;
  /** What the panel is called on screen, which its corner is named after. */
  name: string;
}

/**
 * One grid item, and the panel it holds said to what the grid appends to it.
 *
 * The library builds one resize-handle factory for the whole grid and hands
 * it only the axis — but what that factory returns is appended to *this*
 * element's children (`react-resizable` clones the item with its handles
 * after whatever it already held). So the corner renders inside this
 * component, and a context provided here reaches it: that is how one corner
 * knows it is 「北区订单」's and not the next panel's. Whatever the grid puts
 * on the item — its class, its position, its ref, the drag listeners —
 * passes through to the `div` untouched.
 */
export function PanelGridItem({
  panelId,
  name,
  children,
  ...item
}: PanelGridItemProps) {
  return (
    <div data-panel-id={panelId} {...item}>
      <PanelItemContext.Provider value={{ id: panelId, name }}>
        {children}
      </PanelItemContext.Provider>
    </div>
  );
}

export interface PanelResizeHandleProps {
  /** Which corner or edge the library asked for; `se` unless told otherwise. */
  axis: string;
  ref: React.Ref<HTMLElement>;
  /** Told which panel and which step; the grid holds the geometry. */
  onStep(panelId: string, step: ArrangeStep): void;
}

/**
 * The corner the pointer drags to resize, named and focusable.
 *
 * Named after its panel (「调整『北区订单』的大小」): a board of six panels
 * used to offer six corners under one name, and a reader walking the page
 * could not tell which one they were on. The panel comes from
 * `PanelGridItem`, which the library appends this to. Outside one there is
 * no panel to name or to resize, so the corner says so generically and
 * answers no key rather than guessing at the first panel.
 */
export function PanelResizeHandle({
  axis,
  ref,
  onStep,
}: PanelResizeHandleProps) {
  const messages = useViewMessages();
  const panel = useContext(PanelItemContext);
  return (
    <IconTooltip
      label={
        panel
          ? messages.label('label.panel.resize', { title: panel.name })
          : messages.label('label.panel.resize-any')
      }
      render={
        <button
          // A plain element rather than the vendored `Button`: the class is
          // the library's own hook for placing and drawing the corner, and
          // `Button` would bring a background, a height and a radius that
          // all have to be unset again. The theme owns the colour, and the
          // focus ring — a 20px square upstream only paints while a pointer
          // is over the panel — is one rule in `styles.css`.
          type="button"
          data-slot="panel-resize"
          aria-keyshortcuts={ARROW_KEYS}
          className={`react-resizable-handle react-resizable-handle-${axis}`}
          ref={ref as React.Ref<HTMLButtonElement>}
          onKeyDown={(event: React.KeyboardEvent<HTMLElement>) => {
            const step = SIZE_KEYS[event.key];
            if (!step || !panel) return;
            event.preventDefault();
            onStep(panel.id, step);
          }}
        />
      }
    />
  );
}

export interface PanelArrangeMenuProps extends PanelGripProps {
  /**
   * Whether a command would move the panel at all: one off the grid's edge,
   * or "down" for the last panel of its column on a board that floats
   * panels up, would not (`arrangePanel` answers `null`). Every command is
   * offered when it is left out.
   */
  available?: (step: ArrangeStep) => boolean;
}

/**
 * The eight commands in words.
 *
 * A command that would change nothing is disabled rather than absent: where
 * a panel can go is a property of this moment, not a permission (D4), and a
 * menu whose entries come and go is one nobody can learn.
 */
export function PanelArrangeMenu({
  title,
  available = () => true,
  onStep,
}: PanelArrangeMenuProps) {
  const messages = useViewMessages();
  const group = (
    label: MessageKey,
    commands: readonly (readonly [ArrangeStep, MessageKey, React.FC])[],
  ) => (
    <DropdownMenuGroup>
      <DropdownMenuLabel>{messages.label(label)}</DropdownMenuLabel>
      {commands.map(([step, key, Icon]) => (
        <DropdownMenuItem
          key={step}
          data-slot={`panel-arrange-${step}`}
          disabled={!available(step)}
          onClick={() => onStep(step)}
        >
          <Icon />
          {messages.label(key)}
        </DropdownMenuItem>
      ))}
    </DropdownMenuGroup>
  );

  return (
    <DropdownMenu>
      <IconTooltip
        label={messages.label('label.panel.arrange', { title })}
        render={
          <DropdownMenuTrigger
            render={
              <Button
                data-slot="panel-arrange"
                variant="ghost"
                size="icon-sm"
              />
            }
          />
        }
      >
        <MoveIcon />
      </IconTooltip>
      <DropdownMenuContent align="start">
        {group('label.panel.arrange-move', MOVE_COMMANDS)}
        <DropdownMenuSeparator />
        {group('label.panel.arrange-size', SIZE_COMMANDS)}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
