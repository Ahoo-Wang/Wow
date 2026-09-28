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
import { expect, screen, userEvent, waitFor, within } from 'storybook/test';
import { formatMessage, zhCN } from '@ahoo-wang/wow-view-engine/ui';

/*
 * What more than one of the record workbench's regression files
 * (`RecordWorkbench*.test.stories.tsx`) reads the screen with. Private to them.
 */

/** The shared view's condition and sort, as the source answered them. */
export const PENDING_BY_AMOUNT = ['SO-1003', 'SO-1005', 'SO-1001', 'SO-1006'];

/** One catalogue sentence with its numbers filled in, as the bar writes it. */
export const say = (key: string, params: Record<string, string | number>) =>
  formatMessage(zhCN, key, params);

/** The bar under the rows. */
export const paginationBar = (canvasElement: HTMLElement) =>
  canvasElement.querySelector<HTMLElement>('[data-slot="record-pagination"]')!;

/** Presses Save, whatever it is about to come to. */
export async function save(canvas: ReturnType<typeof within>): Promise<void> {
  await pressWhenEnabled(
    canvas.getByRole('button', { name: zhCN['label.save.save'] }),
  );
  await confirmSharedSave();
}

/**
 * Saving over a shared view asks first (2026-09-23 audit): these stories'
 * views are shared, so every save in place goes through the confirmation,
 * which names the view and writes on 「更新给所有人」.
 */
export async function confirmSharedSave(): Promise<void> {
  const dialog = await screen.findByRole('alertdialog', {
    name: zhCN['label.save.shared-heading'],
  });
  await userEvent.click(
    within(dialog).getByRole('button', {
      name: zhCN['label.save.shared-confirm'],
    }),
  );
  // Gone before the play reads on: while it is up, the page under it is
  // hidden from the accessibility tree the queries read.
  await waitFor(() =>
    expect(
      screen.queryByRole('alertdialog', {
        name: zhCN['label.save.shared-heading'],
      }),
    ).toBeNull(),
  );
}

/**
 * An outcome reaches the screen one render before the command's own progress
 * clears, and every button answering an outcome is disabled while it is set.
 * A click in that gap hits a disabled button and is swallowed.
 */
export async function pressWhenEnabled(button: HTMLElement): Promise<void> {
  await waitFor(() => expect(button).not.toBeDisabled());
  await userEvent.click(button);
}

/**
 * Opens the manager from the sidebar's gear and waits for one row of it.
 *
 * The dialog fades in over a list the engine is still reading, so the row is
 * awaited rather than read at once. It is also the only thing worth waiting
 * for here: one of these scenes opens a view whose conditions match nothing,
 * so there is no table on the page to wait on instead.
 */
export async function openManager(
  canvas: ReturnType<typeof within>,
  title: string,
): Promise<HTMLElement> {
  await userEvent.click(
    await canvas.findByRole('button', {
      name: zhCN['label.manage.open'],
    }),
  );
  await within(document.body).findByRole('dialog');
  let found: HTMLElement | null = null;
  await waitFor(() => {
    found = managerRow(title);
  });
  return found!;
}

/** One row of the manager, by the title it shows or holds in its input. */
export function managerRow(title: string): HTMLElement {
  const found = [
    ...document.querySelectorAll<HTMLElement>('[data-slot="view-manager-row"]'),
  ].find(
    candidate =>
      candidate.textContent?.includes(title) ||
      [...candidate.querySelectorAll('input')].some(field =>
        field.value.includes(title),
      ),
  );
  if (!found) throw new Error(`no row for ${title}`);
  return found;
}

/**
 * Whichever delete confirmation is on screen.
 *
 * By role rather than by the question's words: the question names the view
 * it is about (`label.delete.confirm` carries `{title}`), and the two
 * confirmations of a conflicted delete may not name the same one.
 */
export async function deleteDialog(): Promise<HTMLElement> {
  return within(document.body).findByRole('alertdialog');
}

/** One column header, found by the label it shows. */
/**
 * Adds a column to the sort from its header: Shift held while clicking. A
 * plain click sorts by the column alone, so this is the gesture that stacks
 * one. One `setup()` instance, so the Shift the keyboard holds is on the
 * pointer too — the direct API keeps no state between calls.
 */
export async function addSort(
  table: HTMLElement,
  label: string,
): Promise<void> {
  const user = userEvent.setup();
  await user.keyboard('{Shift>}');
  await user.click(headerOf(table, label).querySelector('button')!);
  await user.keyboard('{/Shift}');
}

export function headerOf(
  table: HTMLElement,
  label: string,
): HTMLTableCellElement {
  const found = [
    ...table.querySelectorAll<HTMLTableCellElement>('thead th'),
  ].find(
    cell =>
      cell.querySelector('[data-slot="column-label"]')?.textContent?.trim() ===
      label,
  );
  if (!found) throw new Error(`No column is headed "${label}".`);
  return found;
}

/** The badge in the first row's cell under a column, if it wears one. */
export function badgeIn(table: HTMLElement, label: string): HTMLElement | null {
  const row = (table as HTMLTableElement).tBodies[0]?.rows[0];
  const cell = row?.cells[headerOf(table, label).cellIndex];
  return cell?.querySelector<HTMLElement>('[data-slot="badge"]') ?? null;
}

/** Where a sorted column sits in the order, as its header shows it. */
export function positionOf(
  table: HTMLElement,
  label: string,
): string | undefined {
  return headerOf(table, label)
    .querySelector('[data-slot="sort-position"]')
    ?.textContent?.trim();
}

/** The scope each summary row is labelled with, top to bottom. */
export function scopeLabels(table: HTMLElement): string[] {
  return [...table.querySelectorAll('tfoot [data-slot="summary-scope"]')].map(
    node => node.textContent?.trim() ?? '',
  );
}

/** One view's row in the sidebar, by the name it shows. */
export function listItem(
  canvasElement: HTMLElement,
  title: string,
): HTMLElement {
  const found = [
    ...canvasElement.querySelectorAll<HTMLElement>(
      '[data-slot="view-list"] [data-slot="view-group"] button',
    ),
  ].find(item => item.textContent?.includes(title));
  if (!found) throw new Error(`The list has no view called "${title}".`);
  return found;
}

/**
 * Resolves once a transitioning value has stopped changing: two reads 50ms
 * apart that agree. A value with no transition agrees at once.
 */
export async function settled(read: () => string): Promise<void> {
  await waitFor(async () => {
    const before = read();
    await new Promise(resolve => setTimeout(resolve, 50));
    if (read() !== before) throw new Error('The value is still moving.');
  });
}

/**
 * Presses Tab until the element has focus, so `:focus-visible` holds.
 *
 * A control inside a `role="toolbar"` is not its own tab stop — the bar is
 * one stop and the arrows move along it (Base UI's `Toolbar`) — so the keys
 * pressed here are the keys a keyboard would actually press: Tab as far as
 * the bar, then ArrowRight to the control.
 */
export async function tabTo(target: HTMLElement): Promise<void> {
  const toolbar = target.closest('[role="toolbar"]');
  for (let presses = 0; presses < 80; presses += 1) {
    if (document.activeElement === target) return;
    if (toolbar?.contains(document.activeElement)) break;
    await userEvent.tab();
  }
  for (let presses = 0; toolbar && presses < 20; presses += 1) {
    if (document.activeElement === target) return;
    await userEvent.keyboard('{ArrowRight}');
  }
  if (document.activeElement === target) return;
  throw new Error('Tab never reached the target.');
}

/**
 * Whether the browser would hand a click at the middle of this box to the box
 * itself.
 *
 * The one question `z-index` cannot be read off a stylesheet to answer: what
 * paints in front depends on every stacking context between here and the
 * root, and this asks the engine that decides it.
 */
export function inFrontOf(element: HTMLElement): boolean {
  const box = element.getBoundingClientRect();
  const hit = element.ownerDocument.elementFromPoint(
    Math.round(box.left + box.width / 2),
    Math.round(box.top + box.height / 2),
  );
  return hit !== null && element.contains(hit);
}

/**
 * The tooltip that is showing, if one is.
 *
 * Base UI leaves the popup in the document while it animates away, so
 * "showing" is the `data-open` on it rather than its presence — otherwise
 * "no tooltip here" would be true only after the fade.
 */
export function tooltipOn(doc: Document): HTMLElement | null {
  return doc.querySelector<HTMLElement>(
    '[data-slot="tooltip-content"][data-open]',
  );
}
