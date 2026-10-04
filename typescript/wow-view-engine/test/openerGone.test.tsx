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

/**
 * A popup whose opener has gone hands the keyboard to a place of its own,
 * not to `<body>` (WCAG 2.4.3). A popup gives the keyboard back to what
 * opened it as it closes; a refresh, a cleared selection or a removed panel
 * can take that control away while the popup is open, and a focus sent to a
 * control no longer on the page falls to the body, where the next Tab starts
 * the page again. Every popup of `kit/popups.tsx` that hands the keyboard
 * back is listed below, and each is opened, has its opener taken away and is
 * closed: the keyboard lands on the nearest control still standing around
 * where the opener was (`landingFor`, `kit/focus.ts`).
 */

import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import {
  AlertDialog,
  AlertDialogTitle,
  AlertDialogTrigger,
} from '../src/ui/components/alert-dialog.js';
import {
  Dialog,
  DialogTitle,
  DialogTrigger,
} from '../src/ui/components/dialog.js';
import {
  DropdownMenu,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '../src/ui/components/dropdown-menu.js';
import { Popover, PopoverTrigger } from '../src/ui/components/popover.js';
import {
  Select,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../src/ui/components/select.js';
import * as popups from '../src/ui/kit/popups.js';
import {
  AlertDialogContent,
  DialogContent,
  DropdownMenuContent,
  PopoverContent,
  SelectContent,
  SheetContent,
} from '../src/ui/kit/popups.js';
import { ViewSurface } from '../src/ui/index.js';

afterEach(cleanup);

/** One popup that hands the keyboard back: its root, trigger and content. */
interface Kind {
  name: string;
  slot: string;
  /**
   * Whether its trigger and its list are one control, which goes whole: a
   * select. It is only ever taken away together with its popup.
   */
  whole?: boolean;
  /** The popup and, while `opener` holds, its trigger. */
  draw(props: Drawn): ReactNode;
}

interface Drawn {
  open: boolean;
  setOpen(open: boolean): void;
  opener: boolean;
  finalFocus?: () => HTMLElement | boolean;
}

/** The caller's `finalFocus`, where the case gives one. */
const asked = ({ finalFocus }: Drawn) => (finalFocus ? { finalFocus } : {});

/** Every popup of `kit/popups.tsx` that hands the keyboard back. */
const HANDS_BACK: readonly Kind[] = [
  {
    name: 'AlertDialogContent',
    slot: 'alert-dialog-content',
    draw: drawn => (
      <AlertDialog open={drawn.open} onOpenChange={drawn.setOpen}>
        {drawn.opener && (
          <AlertDialogTrigger data-testid="opener">Delete</AlertDialogTrigger>
        )}
        <AlertDialogContent {...asked(drawn)}>
          <AlertDialogTitle>Delete</AlertDialogTitle>
          <button type="button">Inside</button>
        </AlertDialogContent>
      </AlertDialog>
    ),
  },
  {
    name: 'DialogContent',
    slot: 'dialog-content',
    draw: drawn => (
      <Dialog open={drawn.open} onOpenChange={drawn.setOpen}>
        {drawn.opener && (
          <DialogTrigger data-testid="opener">Save as</DialogTrigger>
        )}
        <DialogContent {...asked(drawn)}>
          <DialogTitle>Save as</DialogTitle>
        </DialogContent>
      </Dialog>
    ),
  },
  {
    name: 'SheetContent',
    slot: 'sheet-content',
    draw: drawn => (
      <Dialog open={drawn.open} onOpenChange={drawn.setOpen}>
        {drawn.opener && (
          <DialogTrigger data-testid="opener">Record</DialogTrigger>
        )}
        <SheetContent {...asked(drawn)}>
          <DialogTitle>Record</DialogTitle>
        </SheetContent>
      </Dialog>
    ),
  },
  {
    name: 'DropdownMenuContent',
    slot: 'dropdown-menu-content',
    draw: drawn => (
      <DropdownMenu open={drawn.open} onOpenChange={drawn.setOpen}>
        {drawn.opener && (
          <DropdownMenuTrigger data-testid="opener">
            Actions
          </DropdownMenuTrigger>
        )}
        <DropdownMenuContent {...asked(drawn)}>
          <DropdownMenuItem>Rename</DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    ),
  },
  {
    name: 'PopoverContent',
    slot: 'popover-content',
    draw: drawn => (
      <Popover open={drawn.open} onOpenChange={drawn.setOpen}>
        {drawn.opener && (
          <PopoverTrigger data-testid="opener">Columns</PopoverTrigger>
        )}
        <PopoverContent {...asked(drawn)}>
          <button type="button">Inside</button>
        </PopoverContent>
      </Popover>
    ),
  },
  {
    name: 'SelectContent',
    slot: 'select-content',
    whole: true,
    draw: drawn => (
      <Select open={drawn.open} onOpenChange={drawn.setOpen} defaultValue="a">
        {drawn.opener && (
          <SelectTrigger data-testid="opener" aria-label="Page size">
            <SelectValue />
          </SelectTrigger>
        )}
        <SelectContent {...asked(drawn)}>
          <SelectItem value="a">A</SelectItem>
          <SelectItem value="b">B</SelectItem>
        </SelectContent>
      </Select>
    ),
  },
];

/**
 * The rest of `kit/popups.tsx`, and why the keyboard is never theirs to hand
 * back: a submenu's goes back to its menu, which goes with it or stays; a
 * combobox's list never takes it from its input; a tooltip never takes it.
 */
const TAKES_NO_KEYBOARD = [
  'ComboboxContent',
  'DropdownMenuSubContent',
  'TooltipContent',
];

/** What a case does to the page while the popup is open. */
interface Controls {
  /** Takes the trigger away and leaves the popup open. */
  removeOpener(): void;
  /** Takes the trigger and the popup away together, as a refresh takes a row. */
  removeAll(): void;
  close(): void;
}

function Page({
  kind,
  onControls,
  namesOpener = false,
}: {
  kind: Kind;
  onControls(controls: Controls): void;
  /** The caller names the opener as where the keyboard goes back to. */
  namesOpener?: boolean;
}) {
  const Draw = kind.draw;
  const [open, setOpen] = useState(false);
  const [opener, setOpener] = useState(true);
  const [drawn, setDrawn] = useState(true);
  const named = useRef<HTMLElement | null>(null);
  useEffect(() => {
    named.current ??= screen.queryByTestId('opener');
  });
  useEffect(() => {
    onControls({
      removeOpener: () => setOpener(false),
      removeAll: () => setDrawn(false),
      close: () => setOpen(false),
    });
  }, [onControls]);
  const nameOpener = useCallback(() => named.current ?? true, []);
  return (
    <ViewSurface theme="light">
      <div role="toolbar" aria-label="Band">
        <button type="button">Stays</button>
        <div data-testid="row">
          {drawn && (
            <Draw
              open={open}
              setOpen={setOpen}
              opener={opener}
              {...(namesOpener ? { finalFocus: nameOpener } : {})}
            />
          )}
        </div>
      </div>
    </ViewSurface>
  );
}

const openerOf = () => screen.queryByTestId('opener');

const popupOf = (slot: string) =>
  document.querySelector<HTMLElement>(`[data-slot="${slot}"]`);

/**
 * Whether the popup has closed: gone from the page, or — a select keeps its
 * list mounted to align the next opening — hidden.
 */
const closed = (slot: string) => {
  const popup = popupOf(slot);
  return popup === null || popup.closest('[hidden]') !== null;
};

/** Opens `kind` by its trigger; what is done to the page then is the case's. */
async function opened(kind: Kind, namesOpener = false): Promise<Controls> {
  const user = userEvent.setup();
  let controls: Controls | undefined;
  render(
    <Page
      kind={kind}
      onControls={given => (controls = given)}
      namesOpener={namesOpener}
    />,
  );
  await user.click(openerOf()!);
  // Open, and holding the keyboard: a select's list is on the page before
  // it opens.
  await waitFor(() => {
    expect(closed(kind.slot)).toBe(false);
    expect(popupOf(kind.slot)?.contains(document.activeElement)).toBe(true);
  });
  return controls!;
}

/** Takes the trigger away, then closes the popup. */
async function closeWithOpenerGone(kind: Kind, namesOpener = false) {
  const controls = await opened(kind, namesOpener);
  act(() => controls.removeOpener());
  expect(openerOf()).toBeNull();
  act(() => controls.close());
  await waitFor(() => expect(closed(kind.slot)).toBe(true));
}

/** Takes the trigger and the open popup away together. */
async function removeWithItsOpener(kind: Kind, namesOpener = false) {
  const controls = await opened(kind, namesOpener);
  act(() => controls.removeAll());
  expect(openerOf()).toBeNull();
  await waitFor(() => expect(popupOf(kind.slot)).toBeNull());
}

/** The nearest control still standing around where the opener was. */
async function landedBeside() {
  await waitFor(() => expect(document.activeElement).not.toBe(document.body));
  expect(document.activeElement).toBe(
    screen.getByRole('button', { name: 'Stays' }),
  );
}

const OUTLIVING = HANDS_BACK.filter(kind => !kind.whole);

describe('a popup whose opener has gone', () => {
  it('lists every popup the kit draws', () => {
    expect(
      [...HANDS_BACK.map(kind => kind.name), ...TAKES_NO_KEYBOARD].sort(),
    ).toEqual(Object.keys(popups).sort());
  });

  it.each(OUTLIVING)(
    'a $name closed after it lands the keyboard beside it, not on the page',
    async kind => {
      await closeWithOpenerGone(kind);
      await landedBeside();
    },
  );

  it.each(OUTLIVING)(
    'a $name whose caller names the gone opener lands beside it too',
    async kind => {
      // The board's dialogs, the export menu, the record's detail: each
      // names where the keyboard goes, and the place it names can go first.
      await closeWithOpenerGone(kind, true);
      await landedBeside();
    },
  );

  it.each(HANDS_BACK)(
    'a $name taken away with its opener lands the keyboard beside them',
    async kind => {
      // A row's menu or its page-size select under a refresh that filtered
      // the row out: the trigger and the open popup go in one render.
      await removeWithItsOpener(kind);
      await landedBeside();
    },
  );
});

describe('a popup closed with its opener there', () => {
  it.each(HANDS_BACK)('a $name hands the keyboard back to it', async kind => {
    const controls = await opened(kind);
    const opener = openerOf();
    act(() => controls.close());
    await waitFor(() => expect(closed(kind.slot)).toBe(true));

    await waitFor(() => expect(document.activeElement).toBe(opener));
  });
});
