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
  useId,
  useLayoutEffect,
  useRef,
  useState,
} from 'react';
import type * as React from 'react';
import { ArrowDownIcon, ArrowUpIcon, GripVerticalIcon } from 'lucide-react';
import type { ArrangeStep, OrderStep } from '../dashboard/index.js';
import { useListFocus } from './analysis/listFocus.js';
import { IconButton, IconTooltip } from './IconButton.js';
import { useViewMessages } from './MessagesProvider.js';

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
 * One handle on the panel's header does both (V-02): a pointer drags it,
 * and Enter or Space on it starts arranging — the arrows move the panel,
 * Shift and the arrows resize it, Enter or Space ends, Escape puts it back.
 * It used to be two handles side by side, 「移动」 (the grip, arrows moving)
 * and 「摆放」 (a menu of the eight commands), which read as one thing twice.
 * The south-east corner still resizes by pointer, and by its arrows.
 */

/** What each arrow key means while arranging, and on the resize corner. */
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

/** The keys the corner answers, said on the element rather than in its name. */
const ARROW_KEYS = 'ArrowUp ArrowDown ArrowLeft ArrowRight';

/** The keys the handle answers: Enter or Space starts and ends arranging. */
const HANDLE_KEYS = `Enter Space ${ARROW_KEYS} Shift+ArrowUp Shift+ArrowDown Shift+ArrowLeft Shift+ArrowRight Escape`;

export interface PanelHandleProps {
  /** What the panel is called, so the handle says which one it is. */
  title: string;
  /** One step; answers whether the panel moved at all. */
  onStep(step: ArrangeStep): boolean;
  /** Escape: take back the steps this arranging took, however many. */
  onCancel(steps: number): void;
}

/**
 * The panel's one handle: a pointer drags it, and a keyboard arranges with
 * it (V-02).
 *
 * `data-slot="panel-grip"` is what the grid's `dragConfig.handle` selects,
 * so the drag is the library's. Enter or Space — a click with no pointer
 * behind it, `detail === 0`, which is also what a screen reader's "press"
 * is — starts arranging: the handle is pressed (`aria-pressed`), the
 * board's voice says which keys do what, and each step says where the
 * panel landed (`label.panel.placed`). Enter or Space again, or leaving
 * the handle, keeps where it is; Escape takes every step back, one board
 * step each (`edit.undo`), so it lands where it started. A pointer's click
 * without a drag does nothing: it is the start of a drag that did not
 * happen, not a request to arrange.
 *
 * The arrows do nothing until arranging starts, so a reader walking the
 * header with the arrows never moves a panel by accident.
 */
export function PanelHandle({ title, onStep, onCancel }: PanelHandleProps) {
  const messages = useViewMessages();
  const say = useContext(PanelItemContext)?.say;
  const hint = useId();
  const button = useRef<HTMLButtonElement>(null);
  const [arranging, setArranging] = useState(false);
  // Read by the blur's check a frame later, which a render may not have
  // reached: the steps and the mode as they are, not as they were drawn.
  const live = useRef({ arranging: false, steps: 0 });
  const end = (keep: boolean) => {
    if (!live.current.arranging) return;
    const { steps } = live.current;
    live.current = { arranging: false, steps: 0 };
    setArranging(false);
    if (!keep && steps > 0) onCancel(steps);
    say?.(
      messages.label(
        keep ? 'label.panel.arranged' : 'label.panel.arrange-cancelled',
        { title },
      ),
    );
  };
  // A step can move the panel's element among its siblings, and a moved
  // element can drop the keyboard: it is put back while arranging.
  useLayoutEffect(() => {
    if (!arranging || document.activeElement === button.current) return;
    const active = document.activeElement;
    if (active === null || active === document.body) button.current?.focus();
  });
  return (
    <>
      <IconButton
        ref={button}
        type="button"
        data-slot="panel-grip"
        label={messages.label('label.panel.handle', { title })}
        aria-pressed={arranging}
        aria-describedby={hint}
        aria-keyshortcuts={HANDLE_KEYS}
        variant={arranging ? 'secondary' : 'ghost'}
        size="icon-sm"
        className="shrink-0 cursor-move"
        onClick={(event: React.MouseEvent) => {
          if (event.detail !== 0) return;
          if (live.current.arranging) {
            end(true);
            return;
          }
          live.current = { arranging: true, steps: 0 };
          setArranging(true);
          say?.(messages.label('label.panel.arranging', { title }));
        }}
        onKeyDown={(event: React.KeyboardEvent) => {
          if (!live.current.arranging) return;
          if (event.key === 'Escape') {
            // Before a fill or a dialog behind it reads the same key.
            event.preventDefault();
            event.stopPropagation();
            end(false);
            return;
          }
          const step = (event.shiftKey ? SIZE_KEYS : MOVE_KEYS)[event.key];
          if (!step) return;
          // The arrows are the handle's while arranging, whether or not the
          // grid has room — otherwise a press against an edge scrolls.
          event.preventDefault();
          if (onStep(step)) live.current.steps += 1;
          else say?.(messages.label('label.panel.arrange-stuck', { title }));
        }}
        onBlur={() => {
          // A frame later: a step that moved the element took the keyboard
          // with it for a moment, and the effect above has put it back.
          requestAnimationFrame(() => {
            if (document.activeElement !== button.current) end(true);
          });
        }}
      >
        <GripVerticalIcon />
      </IconButton>
      <span id={hint} hidden>
        {messages.label('label.panel.handle-hint')}
      </span>
    </>
  );
}

/**
 * Which panel a grid item holds, for the corner the library appends to it,
 * and the board's voice, for the handle arranging it.
 */
interface PanelItemIdentity {
  id: string;
  /** What the panel is called on screen — never its id. */
  name: string;
  say?(message: string): void;
}

const PanelItemContext = createContext<PanelItemIdentity | null>(null);

export interface PanelGridItemProps extends React.ComponentProps<'div'> {
  panelId: string;
  /** What the panel is called on screen, which its corner is named after. */
  name: string;
  /** The board's voice, which the panel's handle says its steps in. */
  say?(message: string): void;
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
  say,
  children,
  ...item
}: PanelGridItemProps) {
  return (
    <div data-panel-id={panelId} {...item}>
      <PanelItemContext.Provider value={{ id: panelId, name, say }}>
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

export interface PanelOrderProps {
  /** What the panel is called, so each button says which one it moves. */
  title: string;
  /** Where the panel stands in the column, from 0, and how many there are. */
  index: number;
  total: number;
  onMove(step: OrderStep): void;
}

/**
 * 「上移」／「下移」 on a panel in the one-column reading (D22 J), where the
 * grip and the corner are not: a step along the column, which the runtime
 * writes back onto the grid as the layout that reads that way
 * (`reorderPanel`). The column is the order a reader goes down, so the
 * buttons are the tray's own pattern — named after the panel, disabled at
 * either end, and the keyboard kept on the button that moved it at the
 * place the panel landed, or on the other one once that way has run out
 * (`useListFocus`).
 */
export function PanelOrder({ title, index, total, onMove }: PanelOrderProps) {
  const messages = useViewMessages();
  const focus = useListFocus({
    list: '[data-slot="dashboard-tab-panel"]',
    item: '[data-slot="dashboard-panel"]',
  });
  const button = (step: OrderStep, Icon: React.FC, disabled: boolean) => (
    <IconButton
      type="button"
      data-slot="panel-order"
      data-move={step}
      label={messages.label(
        step === 'up' ? 'label.panel.order-up' : 'label.panel.order-down',
        { title },
      )}
      variant="ghost"
      size="icon-sm"
      className="shrink-0"
      disabled={disabled}
      onClick={(event: React.MouseEvent<HTMLElement>) => {
        focus.moved(event, step === 'up' ? index - 1 : index + 1, step);
        onMove(step);
      }}
    >
      <Icon />
    </IconButton>
  );
  return (
    <>
      {button('up', ArrowUpIcon, index === 0)}
      {button('down', ArrowDownIcon, index >= total - 1)}
    </>
  );
}
