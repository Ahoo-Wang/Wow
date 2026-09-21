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
import type { PanelLayout } from '../model/index.js';
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

/**
 * Placing a panel without a pointer.
 *
 * `react-grid-layout` 2.2 has no keyboard sensor — the drag is
 * `react-draggable`'s, which listens for mouse and touch and nothing else,
 * and the resize handle is `react-resizable`'s, the same. So the keyboard
 * equivalent is not a setting to turn on; it is a set of commands of our
 * own, over the one thing a gesture produces: a new `PanelLayout`.
 *
 * One step is one grid cell, which is what a drag lands on anyway — the
 * library snaps to the column and the row — so the keyboard and the pointer
 * write the same kind of value and `dashboard.place` cannot tell them apart.
 *
 * Both handles answer the arrow keys for what that handle does by pointer:
 * the grip moves, the south-east corner resizes. The menu says the same
 * eight commands in words, because a key that is only discoverable by
 * pressing it is not discoverable.
 */
export type ArrangeStep =
  | 'left'
  | 'right'
  | 'up'
  | 'down'
  | 'wider'
  | 'narrower'
  | 'taller'
  | 'shorter';

/**
 * The layout one step lands on, or `null` when the grid has no room for it.
 *
 * The bounds are the kernel's, so a command can never produce a layout
 * `validateDashboard` would refuse: `x` and `y` are non-negative, `w` and
 * `h` are at least one cell, and `x + w` stays inside the grid. Down and
 * taller have no far edge — a dashboard grows downwards, and the kernel
 * puts no ceiling on `y` or `h`.
 */
export function arrangeLayout(
  layout: PanelLayout,
  step: ArrangeStep,
  columns: number,
): PanelLayout | null {
  const { x, y, w, h } = layout;
  switch (step) {
    case 'left':
      return x > 0 ? { ...layout, x: x - 1 } : null;
    case 'right':
      return x + w < columns ? { ...layout, x: x + 1 } : null;
    case 'up':
      return y > 0 ? { ...layout, y: y - 1 } : null;
    case 'down':
      return { ...layout, y: y + 1 };
    case 'wider':
      return x + w < columns ? { ...layout, w: w + 1 } : null;
    case 'narrower':
      return w > 1 ? { ...layout, w: w - 1 } : null;
    case 'taller':
      return { ...layout, h: h + 1 };
    case 'shorter':
      return h > 1 ? { ...layout, h: h - 1 } : null;
  }
}

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
 * The library builds one handle factory for the whole grid and hands it only
 * the axis, so the panel it belongs to is read back off the grid item it was
 * appended to — `DashboardGrid` stamps `data-panel-id` there for exactly
 * this. A per-panel handle is not on offer: `resizeConfig` is a grid-level
 * prop, and the element lands inside the item as a sibling of everything we
 * render, out of reach of any context of ours.
 */
export function PanelResizeHandle({
  axis,
  ref,
  onStep,
}: PanelResizeHandleProps) {
  const messages = useViewMessages();
  return (
    <IconTooltip
      label={messages.label('label.panel.resize')}
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
          className={`react-resizable-handle react-resizable-handle-${axis}`}
          ref={ref as React.Ref<HTMLButtonElement>}
          onKeyDown={(event: React.KeyboardEvent<HTMLElement>) => {
            const step = SIZE_KEYS[event.key];
            if (!step) return;
            const panelId = event.currentTarget
              .closest('[data-panel-id]')
              ?.getAttribute('data-panel-id');
            if (panelId === null || panelId === undefined) return;
            event.preventDefault();
            onStep(panelId, step);
          }}
        />
      }
    />
  );
}

export interface PanelArrangeMenuProps extends PanelGripProps {
  /** Where the panel is now; a command at the edge of the grid is out. */
  layout: PanelLayout;
  columns: number;
}

/**
 * The eight commands in words.
 *
 * A command the grid has no room for is disabled rather than absent: where
 * a panel can go is a property of this moment, not a permission (D4), and a
 * menu whose entries come and go is one nobody can learn.
 */
export function PanelArrangeMenu({
  title,
  layout,
  columns,
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
          disabled={arrangeLayout(layout, step, columns) === null}
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
