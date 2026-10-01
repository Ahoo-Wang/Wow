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
import type { StoryObj } from '@storybook/react-vite';
import { expect, userEvent, waitFor, within } from 'storybook/test';
import { zhCN } from '@ahoo-wang/wow-view-engine/ui';
import displayMeta, {
  WithData as DisplayWithData,
} from './RecordWorkbench.stories.js';
import { addSort, paginationBar, say } from './recordWorkbenchTest.js';
import { outage } from './fixtures.js';

/**
 * The condition editor and the query: its fold and modes, picking fields, and
 * what a query announces. One of the record workbench's regression files, split
 * by concern; they all share one title, so every story keeps its id, and the
 * helpers more than one of them needs are in `recordWorkbenchTest.ts`.
 */
const meta = {
  ...displayMeta,
  title: 'View Engine/组件状态/记录工作台/回归',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out, not left to the spread: Storybook writes a file's own
  // description into a `parameters` of its meta, which would replace the
  // display meta's — and with it the full-screen host application the
  // workbench is meant to be exercised in.
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

/**
 * The editor's fold is driven from the title bar, and its mode from the
 * chevron beside it.
 *
 * Both moved out of the panel: the panel is the conditions, and a control
 * for *how to edit them* sitting among them was a line of chrome over every
 * filter ever written. The dot on the toggle is the one credential a folded
 * editor can still show, so it is asserted here rather than assumed.
 */
export const EditorToggleAndModes: Story = {
  ...DisplayWithData,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');

    // A saved view opens folded, so the panel is not on the page at all.
    await expect(
      canvasElement.querySelector('[data-slot="editor-band"]'),
    ).toBeNull();

    const toggle = canvas.getByRole('button', {
      name: new RegExp(`^${zhCN['label.filter.panel']}`),
    });
    await expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await userEvent.click(toggle);
    await expect(
      canvasElement.querySelector('[data-slot="editor-band"]'),
    ).not.toBeNull();

    // The mode lives beside the toggle now, and drives the panel below it.
    await userEvent.click(
      canvas.getByRole('button', {
        name: zhCN['label.workbench.editor-modes'],
      }),
    );
    const modes = await within(document.body).findByRole('menu');
    await userEvent.click(
      within(modes).getByRole('menuitemradio', {
        name: zhCN['label.filter.advanced'],
      }),
    );

    // Advanced draws the root as a framed block with its operator on it.
    await waitFor(() =>
      expect(
        canvasElement.querySelector('[data-slot="filter-group"]'),
      ).not.toBeNull(),
    );
    // The menu is gone before the story settles. Base UI parks focus-guard
    // sentinels beside an open popup, and axe judges the page as the play
    // leaves it — so a play that opened something closes it, which is what
    // a user does anyway.
    await waitFor(() =>
      expect(document.body.querySelector('[role="menu"]')).toBeNull(),
    );
    await expect(
      canvas.getByRole('button', {
        name: new RegExp(`^${zhCN['label.filter.panel']}`),
      }),
    ).toHaveAccessibleName(
      // Called what it opens: the mode is the menu's checked item, not a
      // word on the button.
      zhCN['label.filter.panel'],
    );
  },
};

/**
 * What a query says out loud, in a browser where a live region is read
 * rather than an attribute in a tree.
 *
 * The rows change under a reader who is not looking at them, and until this
 * every `[aria-live]` on the screen stayed empty from the first press to the
 * last. What is regressed here is the pair, in order and once each: the
 * query says it is running, and then says what came back in the sentence the
 * pagination bar is showing. Sorting is the shortest honest query — it is
 * one `edit` and one `apply`, the same round trip a condition makes.
 */
export const QueryAnnouncedInTheResult: Story = {
  ...DisplayWithData,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    const region = () => {
      const found = canvasElement.querySelector<HTMLElement>(
        '[data-slot="record-announcement"]',
      );
      if (!found) throw new Error('the result has no live region');
      return found;
    };
    const landed = say('label.pagination.total', { count: 4 });

    // The query that opened the view has already been read back.
    await waitFor(() => expect(region()).toHaveTextContent(landed));

    // Everything it says from here on, in order and without repeats.
    const heard: string[] = [];
    const observer = new MutationObserver(() => {
      const text = region().textContent?.trim() ?? '';
      if (text !== '' && heard[heard.length - 1] !== text) heard.push(text);
    });
    // `characterData` as well as `childList`: React writes a new sentence
    // into the text node that is already there, which is not a child list
    // changing.
    observer.observe(region(), {
      characterData: true,
      childList: true,
      subtree: true,
    });

    await addSort(table, '订单号');
    await waitFor(() => expect(heard).toContain(landed));
    observer.disconnect();

    // Two sentences for one query, each said once: nothing repeated, and
    // the running one in between is what makes a second identical result
    // audible at all.
    await expect(heard).toEqual([zhCN['label.status.querying'], landed]);

    // And the bar the sentence was taken from says the same thing, which is
    // the whole reason it is that sentence and not a second wording.
    await expect(paginationBar(canvasElement)).toHaveTextContent(landed);

    // The button that ran it can now be heard as a sort. On screen it is
    // the first field, an arrow and a count — 订单号 joined the saved sort
    // rather than replacing it — and none of that says what the control is.
    const sortButton = canvasElement.querySelector<HTMLElement>(
      '[data-control="sort"]',
    );
    await waitFor(() =>
      expect(sortButton).toHaveAccessibleName(
        `${say('label.sort.button', {
          field: '金额',
          direction: zhCN['label.sort.desc'],
        })} ${say('label.sort.more', { count: 1 })}`,
      ),
    );
    // And the words on it are unchanged by the name it was given.
    await expect(sortButton).toHaveTextContent(
      `金额${say('label.sort.more', { count: 1 })}`,
    );

    // A toggle whose own state a reader hears as one word, so the panel
    // says which column and which edge as well.
    await userEvent.click(
      canvasElement.querySelector<HTMLElement>('[data-control="columns"]')!,
    );
    const body = within(document.body);
    await body.findByText(zhCN['label.columns.title']);
    await userEvent.click(
      body.getByRole('button', {
        name: say('label.columns.pin', { field: '仓库' }),
      }),
    );
    await waitFor(() =>
      expect(
        document.body.querySelector('[data-slot="column-announcement"]'),
      ).toHaveTextContent(/^仓库 已固定/),
    );

    // Closed before the story settles, as every play that opens a popup
    // does: axe judges the page as the play leaves it. Twice, because the
    // control just pressed is showing its own tooltip on top.
    await userEvent.keyboard('{Escape}');
    await userEvent.keyboard('{Escape}');
    await waitFor(() =>
      expect(
        document.body.querySelector('[data-slot="popover-content"]'),
      ).toBeNull(),
    );
  },
};

/**
 * Three fields ticked in one visit to the picker, and three pills for it.
 *
 * The picker used to close on every pick, which made four conditions four
 * round trips. It stays open now, so the thing worth regressing is that a
 * second tick lands while the first is still on screen — and that the third
 * can be ticked without a pointer at all.
 */
export const PickSeveralFields: Story = {
  ...DisplayWithData,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');

    await userEvent.click(
      canvas.getByRole('button', {
        name: new RegExp(`^${zhCN['label.filter.panel']}`),
      }),
    );
    await userEvent.click(
      await canvas.findByRole('button', { name: zhCN['label.filter.add'] }),
    );

    const picker = await within(document.body).findByRole('dialog');
    await expect(picker).toHaveTextContent(zhCN['label.filter.pick-fields']);
    // The view's saved condition is already a tick, which is what makes the
    // list a statement about the filter rather than a menu of things to add.
    await expect(
      within(picker).getByRole('checkbox', { name: '状态' }),
    ).toBeChecked();

    await userEvent.click(
      within(picker).getByRole('checkbox', { name: '仓库' }),
    );
    await userEvent.click(
      within(picker).getByRole('checkbox', { name: '金额' }),
    );
    // And the third with the keyboard alone: from the search line one Tab
    // steps into the grid and Space is what a tick means. The jsdom suite
    // asserts the same thing; this one asserts it in a browser, where the
    // checkbox is a `span` carrying a role rather than an input, and Space
    // is somebody's own key handler rather than the platform's.
    await userEvent.click(
      within(picker).getByRole('textbox', { name: zhCN['label.field.search'] }),
    );
    await userEvent.keyboard('{Tab}');
    await expect(document.activeElement).toBe(
      within(picker).getByRole('checkbox', { name: '订单号' }),
    );
    await userEvent.keyboard(' ');
    await expect(
      within(picker).getByRole('checkbox', { name: '订单号' }),
    ).toBeChecked();
    await userEvent.click(
      within(picker).getByRole('button', {
        name: zhCN['label.filter.pick-done'],
      }),
    );

    // Four conditions now: the saved one, the two ticked with the pointer
    // and the one ticked with the keyboard.
    await waitFor(() =>
      expect(
        canvasElement.querySelectorAll('[data-slot="filter-condition"]'),
      ).toHaveLength(4),
    );
    // And the picker is shut, sentinels and all — see CollapseAndSwitch.
    await waitFor(() =>
      expect(document.body.querySelector('[role="dialog"]')).toBeNull(),
    );
  },
};

/**
 * Changed conditions whose query fails, in each filter mode: the rows stay,
 * and the failure's line says they are the result of the conditions before
 * the change — not only an older result of these (the pre-release review's
 * 「查询失败时仍显示上一次的结果」). Above them the editor already holds the
 * new conditions; without the line, the rows read as their answer.
 */
async function conditionsChangeThenFail(
  canvasElement: HTMLElement,
  mode: 'simple' | 'advanced',
) {
  const canvas = within(canvasElement);
  outage.down = false;
  try {
    const table = await canvas.findByRole('table');
    await waitFor(() =>
      expect(table.querySelectorAll('tbody tr').length).toBeGreaterThan(0),
    );
    const rows = table.querySelectorAll('tbody tr').length;
    await userEvent.click(
      canvas.getByRole('button', {
        name: new RegExp(`^${zhCN['label.filter.panel']}`),
      }),
    );
    if (mode === 'advanced') {
      await userEvent.click(
        canvas.getByRole('button', {
          name: zhCN['label.workbench.editor-modes'],
        }),
      );
      const modes = await within(document.body).findByRole('menu');
      await userEvent.click(
        within(modes).getByRole('menuitemradio', {
          name: zhCN['label.filter.advanced'],
        }),
      );
      await waitFor(() =>
        expect(
          canvasElement.querySelector('[data-slot="filter-group"]'),
        ).not.toBeNull(),
      );
      await waitFor(() =>
        expect(document.body.querySelector('[role="menu"]')).toBeNull(),
      );
    }

    outage.down = true;
    await userEvent.click(
      await canvas.findByRole('button', {
        name: say('label.filter.remove-of', { field: '状态' }),
      }),
    );
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.filter.apply'] }),
    );

    const alert = await canvas.findByRole('alert');
    await waitFor(() =>
      expect(alert).toHaveTextContent(
        zhCN['label.query.stale-conditions'].replace('{error} · ', ''),
      ),
    );
    await expect(alert).not.toHaveTextContent(
      zhCN['label.query.stale'].replace('{error} · ', ''),
    );
    // The rows of the old conditions are still there to read.
    await expect(table.querySelectorAll('tbody tr')).toHaveLength(rows);
  } finally {
    outage.down = false;
  }
}

export const ChangedConditionsFailSimple: Story = {
  ...DisplayWithData,
  args: { ...DisplayWithData.args, behaviour: 'outage' },
  tags: ['!dev', '!autodocs', 'test'],
  play: ({ canvasElement }) =>
    conditionsChangeThenFail(canvasElement, 'simple'),
};

export const ChangedConditionsFailAdvanced: Story = {
  ...DisplayWithData,
  args: { ...DisplayWithData.args, behaviour: 'outage' },
  tags: ['!dev', '!autodocs', 'test'],
  play: ({ canvasElement }) =>
    conditionsChangeThenFail(canvasElement, 'advanced'),
};
