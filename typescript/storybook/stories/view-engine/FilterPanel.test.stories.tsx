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
import { expect, fireEvent, userEvent, waitFor, within } from 'storybook/test';
import { formatMessage, zhCN } from '@ahoo-wang/fetcher-view-engine/ui';
import displayMeta, {
  Advanced as DisplayAdvanced,
  Negated as DisplayNegated,
  NumberList as DisplayNumberList,
  Reference as DisplayReference,
  Simple as DisplaySimple,
  UnregisteredKind as DisplayUnregisteredKind,
  WithTime as DisplayWithTime,
} from './FilterPanel.stories.js';
import { amountOf, readColumn, readTotal } from './readTable.js';

const meta = {
  ...displayMeta,
  title: 'View Engine/数据视图/筛选编辑器/回归',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out, not left to the spread: Storybook writes a file's own
  // description into a `parameters` of its meta, which would replace the
  // display meta's — and with it the full-screen host application the
  // workbench is meant to be exercised in.
  parameters: { ...displayMeta.parameters },
};

/**
 * The amount field's declared format, as the bar itself formats it. The
 * field's `numberFormat` names no language, so it takes the surface's —
 * `HOST_LANGUAGE.locale`, which writes 「¥」 where the machine's own language
 * would write `CN¥`. A number in the reader's language is the whole point of
 * a `locale` prop, and this bar used to be the one place it did not reach.
 */
const yuan = (value: number) =>
  new Intl.NumberFormat('zh-CN', {
    style: 'currency',
    currency: 'CNY',
  }).format(value);

/** What the catalogue in force puts between two conditions. */
const JOIN = zhCN['label.filter.join'];

export default meta;

type Story = StoryObj<typeof displayMeta>;

/** Pending and at least 100: every pending order, in the order they came. */
export const Simple: Story = {
  ...DisplaySimple,
  play: async ({ canvasElement }) => {
    const table = await within(canvasElement).findByRole('table');
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual([
        'SO-1001',
        'SO-1003',
        'SO-1005',
        'SO-1006',
      ]),
    );
    await expect(amountOf(readTotal(table, '金额'))).toBe(6470);
  },
};

/**
 * The rich tree asks for 华东 and, in its OR group, for 华北 or more than
 * 20,000 at once: no order is both, and the table says so — as the saved
 * view it is, asking what it was saved to ask, so the view is what is
 * empty, and its conditions are not offered up to be cleared.
 */
export const Advanced: Story = {
  ...DisplayAdvanced,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(
      await canvas.findByText(zhCN['label.record.empty-view']),
    ).toBeVisible();
    await expect(
      canvas.getByRole('button', { name: zhCN['label.record.empty-edit'] }),
    ).toBeVisible();

    // The bar over the result is the one place this tree is read back as
    // sentences, and it is built from the parts each kind hands over. This
    // is the only fixture carrying all three of the shapes that get it
    // wrong: a named period, a nested group, and a predicate.
    const applied = canvas.getByRole('region', {
      name: zhCN['label.applied.title'],
    });
    const badges = [...applied.querySelectorAll('[data-slot="badge"]')].map(
      badge => badge.textContent?.trim(),
    );

    // Every word below is read back out of the catalogue rather than typed
    // in: the badge is the one line that has to change with `messages`, so a
    // hard-coded expectation would only ever prove that nothing changed.
    await expect(badges).toEqual([
      `仓库 ${zhCN['label.relation.is']} 华东`,
      `状态 ${zhCN['label.operator.IN']} 待出库${JOIN}已发运`,
      // The field's own `numberFormat`, from the same Intl call the bar
      // makes — which currency symbol ICU picks is not what this is about.
      `金额 ${zhCN['label.operator.BETWEEN']} ${yuan(100)} ~ ${yuan(5000)}`,
      // A period, not a range: the operator asks for the window it names.
      `创建时间 ${zhCN['label.operator.BETWEEN']} ${zhCN['label.relative.preset.thisMonth']}`,
      // A group says how its conditions combine before it lists them.
      `${zhCN['label.filter.any-of']} 仓库 ${zhCN['label.relation.is']} 华北` +
        `${JOIN}金额 ${zhCN['label.operator.GT']} ${yuan(20000)}`,
      // And a predicate reads its own conditions out once, under the one
      // operator it holds them by.
      `商品行 ${zhCN['label.operator.ELEMENT_MATCH']} ` +
        `${zhCN['label.filter.all-of']} SKU ${zhCN['label.relation.is']} A-1` +
        `${JOIN}数量 ${zhCN['label.operator.GT']} 2`,
      // Last, the reading nobody wrote: the definition declares the
      // soft-delete dimension and this tree leaves it blank, so the rows
      // are the ones not deleted, and the bar says so (D17-2). The trailing
      // words are the reader's note that this is the default.
      `删除状态 ${zhCN['label.operator.DELETION']} ${zhCN['label.deletion.active']} ` +
        zhCN['label.applied.implied'],
    ]);
  },
};

/**
 * D18-7: a negated condition is a `nor` group of one, and simple mode shows
 * it as the pill with its switch pressed and the word in its sentence. The
 * bar says the whole condition under 排除; pressing the switch again and
 * applying is the plain condition, with the rows to match.
 */
export const Negated: Story = {
  ...DisplayNegated,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    const badges = () =>
      [
        ...canvas
          .getByRole('region', { name: zhCN['label.applied.title'] })
          .querySelectorAll('[data-slot="badge"]'),
      ].map(badge => badge.textContent?.trim());
    const status = `状态 ${zhCN['label.relation.is']} 已取消`;

    // Every order that is not cancelled.
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual([
        'SO-1001',
        'SO-1003',
        'SO-1004',
        'SO-1005',
        'SO-1006',
      ]),
    );
    await expect(badges()[0]).toBe(
      formatMessage(zhCN, 'label.filter.not-of', { condition: status }),
    );

    // The pill: switch pressed, the word in front of the operator.
    await userEvent.click(
      await canvas.findByRole('button', {
        name: new RegExp(`^${zhCN['label.filter.panel']}`),
      }),
    );
    const pill = await canvas.findByRole('group', {
      name: formatMessage(zhCN, 'label.filter.condition-of', {
        field: '状态',
      }),
    });
    const negate = within(pill).getByRole('button', {
      name: formatMessage(zhCN, 'label.filter.negate-of', { field: '状态' }),
    });
    await expect(negate).toHaveAttribute('aria-pressed', 'true');
    await expect(
      pill.querySelector('[data-slot="filter-negated"]'),
    ).toHaveTextContent(zhCN['label.filter.negated']);

    // Pressed again, it is the plain condition — and only that row.
    await userEvent.click(negate);
    await waitFor(() =>
      expect(
        within(
          canvas.getByRole('group', {
            name: formatMessage(zhCN, 'label.filter.condition-of', {
              field: '状态',
            }),
          }),
        ).getByRole('button', {
          name: formatMessage(zhCN, 'label.filter.negate-of', {
            field: '状态',
          }),
        }),
      ).toHaveAttribute('aria-pressed', 'false'),
    );
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.filter.apply'] }),
    );
    await waitFor(() =>
      expect(readColumn(canvas.getByRole('table'), '订单号')).toEqual([
        'SO-1002',
      ]),
    );
    await expect(badges()[0]).toBe(status);
  },
};

/**
 * F-04: a reference field's candidates come from the host's source. The saved
 * view carries the chosen customer's name beside its id, so the pill and the
 * bar say it without a lookup; the list is asked only once opened, and
 * again once typing pauses; a pick lands as `{ id, label }` and the rows
 * follow it.
 */
export const Reference: Story = {
  ...DisplayReference,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const body = within(document.body);
    const table = await canvas.findByRole('table');
    const badges = () =>
      [
        ...canvas
          .getByRole('region', { name: zhCN['label.applied.title'] })
          .querySelectorAll('[data-slot="badge"]'),
      ].map(badge => badge.textContent?.trim());

    // The saved condition, said from the label it carries.
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual(['SO-1001', 'SO-1003']),
    );
    await expect(badges()[0]).toBe(
      `客户 ${zhCN['label.relation.is']} 晨光食品`,
    );

    await userEvent.click(
      await canvas.findByRole('button', {
        name: new RegExp(`^${zhCN['label.filter.panel']}`),
      }),
    );
    const pill = await canvas.findByRole('group', {
      name: formatMessage(zhCN, 'label.filter.condition-of', {
        field: '客户',
      }),
    });
    await expect(within(pill).getByText('晨光食品')).toBeVisible();

    // Opening lists the first page; typing narrows it at the source.
    const input = within(pill).getByRole('combobox', {
      name: formatMessage(zhCN, 'label.filter.value-of', { field: '客户' }),
    });
    await userEvent.click(input);
    await body.findByRole('option', { name: '宏远贸易' });
    await expect(
      body.getByRole('button', { name: zhCN['label.filter.more-candidates'] }),
    ).toBeVisible();
    await userEvent.type(input, '物流');
    await waitFor(() =>
      expect(body.queryByRole('option', { name: '宏远贸易' })).toBeNull(),
    );
    await userEvent.click(
      await body.findByRole('option', { name: '蓝海物流' }),
    );

    // Both are chips now; the first is removed by its own button.
    await expect(within(pill).getByText('蓝海物流')).toBeVisible();
    await userEvent.click(
      within(pill).getByRole('button', {
        name: formatMessage(zhCN, 'label.filter.remove-value', {
          value: '晨光食品',
        }),
      }),
    );
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.filter.apply'] }),
    );
    await waitFor(() =>
      expect(readColumn(canvas.getByRole('table'), '订单号')).toEqual([
        'SO-1002',
        'SO-1005',
      ]),
    );
    await expect(badges()[0]).toBe(
      `客户 ${zhCN['label.relation.is']} 蓝海物流`,
    );
  },
};

/**
 * D17-2: a definition that declares the soft-delete dimension shows the
 * records that are not deleted unless a view says otherwise, and the bar
 * says that default without offering to remove it. Choosing «deleted
 * included» is then an ordinary condition — added through the picker,
 * answered in the pill, applied, and removable from the bar.
 */
export const DeletionReading: Story = {
  ...DisplaySimple,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const body = within(document.body);
    await canvas.findByRole('table');
    const bar = () =>
      canvas.getByRole('region', { name: zhCN['label.applied.title'] });
    const implied = () =>
      bar().querySelector('[data-slot="badge"][data-implied]');

    // Nothing written: the default reading is in force, said, and not
    // removable.
    await waitFor(() => expect(implied()).not.toBeNull());
    await expect(implied()?.textContent).toContain(
      `删除状态 ${zhCN['label.operator.DELETION']} ${zhCN['label.deletion.active']}`,
    );
    await expect(implied()?.querySelector('button')).toBeNull();

    // Add the field, answer it, apply.
    await userEvent.click(
      await canvas.findByRole('button', {
        name: new RegExp(`^${zhCN['label.filter.panel']}`),
      }),
    );
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.filter.add'] }),
    );
    await userEvent.click(
      await body.findByRole('checkbox', { name: '删除状态' }),
    );
    await userEvent.click(
      body.getByRole('button', { name: zhCN['label.filter.pick-done'] }),
    );
    await userEvent.click(
      canvas.getByRole('combobox', {
        name: formatMessage(zhCN, 'label.filter.value-of', {
          field: '删除状态',
        }),
      }),
    );
    await userEvent.click(
      await body.findByRole('option', { name: zhCN['label.deletion.all'] }),
    );
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.filter.apply'] }),
    );

    // Written, it is a condition like any other, and no longer the default.
    await waitFor(() =>
      expect(bar().textContent).toContain(zhCN['label.deletion.all']),
    );
    await expect(implied()).toBeNull();
    // And the rows answer it: the soft-deleted order the default hid is in.
    await waitFor(() =>
      expect(readColumn(canvas.getByRole('table'), '订单号')).toContain(
        'SO-1007',
      ),
    );
    await expect(
      within(bar()).getByRole('button', {
        name: formatMessage(zhCN, 'label.filter.unset-of', {
          condition: `删除状态 ${zhCN['label.operator.DELETION']} ${zhCN['label.deletion.all']}`,
        }),
      }),
    ).toBeVisible();
  },
};

/**
 * A `withTime` field's condition carries a time of day, in the same control
 * as the calendar and with one submission (D17-1). The box starts empty,
 * which is the day itself — read at `00:00:00.000` as a start and at
 * `23:59:59.999` as an end — and the summary says the time only where one
 * was given, so the badge never claims a boundary the query did not run to.
 *
 * Only a real browser can drive a native time input, which is why this lives
 * here and not in jsdom.
 */
export const WithTime: Story = {
  ...DisplayWithTime,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await userEvent.click(
      await canvas.findByRole('button', {
        name: new RegExp(`^${zhCN['label.filter.panel']}`),
      }),
    );

    // The calendar and its clock are one control, opened from the pill.
    const trigger = await canvas.findByLabelText(
      formatMessage(zhCN, 'label.filter.value-of', { field: '创建时间' }),
    );
    await userEvent.click(trigger);

    const popup = within(document.body);
    const from = await popup.findByLabelText(zhCN['label.date.time-from']);
    // Both boxes are empty to begin with: that is the whole day, not
    // midnight, and it is what the hint under them says.
    await expect(from).toHaveValue('');
    await expect(popup.getByLabelText(zhCN['label.date.time-to'])).toHaveValue(
      '',
    );

    // Reachable and editable from the keyboard: the box takes focus, and
    // the value is then set the way the browser's own spin fields set it.
    // Synthetic keystrokes do not drive a native time input's segments —
    // they are untrusted, so Chromium ignores them — which is why this
    // changes the value rather than typing six digits into it.
    await userEvent.click(from);
    await expect(from).toHaveFocus();
    fireEvent.change(from, { target: { value: '15:30:00' } });
    await waitFor(() => expect(from).toHaveValue('15:30:00'));
    await userEvent.keyboard('{Escape}');

    // The trigger reads the bound back with the time on the start edge and
    // without one on the end, through the surface's own formatter: a
    // wall-clock string names a time on a clock rather than a moment, so it
    // is shown as written whatever zone the browser is in.
    const shown = (utc: number, withTime: boolean) =>
      new Intl.DateTimeFormat('zh-CN', {
        dateStyle: 'medium',
        ...(withTime ? { timeStyle: 'medium' as const } : {}),
        timeZone: 'UTC',
      }).format(utc);
    await waitFor(() =>
      expect(trigger.textContent).toBe(
        `${shown(Date.UTC(2026, 8, 15, 15, 30), true)} – ` +
          shown(Date.UTC(2026, 8, 17), false),
      ),
    );

    await userEvent.click(
      await canvas.findByRole('button', { name: zhCN['label.filter.apply'] }),
    );

    // And the applied badge says the same: a bound with a time of day says
    // it, one without stays a day.
    const applied = canvas.getByRole('region', {
      name: zhCN['label.applied.title'],
    });
    await waitFor(() =>
      expect(
        [...applied.querySelectorAll('[data-slot="badge"]')].map(badge =>
          badge.textContent?.trim(),
        ),
      ).toEqual([
        `创建时间 ${zhCN['label.operator.BETWEEN']} ` +
          `${shown(Date.UTC(2026, 8, 15, 15, 30), true)} ~ ` +
          shown(Date.UTC(2026, 8, 17), false),
        // The definition's soft-delete dimension, left blank: said as the
        // default (D17-2).
        `删除状态 ${zhCN['label.operator.DELETION']} ${zhCN['label.deletion.active']} ` +
          zhCN['label.applied.implied'],
      ]),
    );
  },
};

/**
 * A numeric `IN` takes as many values as the kernel compiles. The editor used
 * to borrow the range's pair of boxes, so a third value had nowhere to go —
 * and only a test that types into the editor catches that, because the kernel
 * itself never refused the array.
 */
export const NumberList: Story = {
  ...DisplayNumberList,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    // A saved view opens with its editor folded.
    await userEvent.click(
      await canvas.findByRole('button', {
        name: new RegExp(`^${zhCN['label.filter.panel']}`),
      }),
    );

    // Each chip's ✕ names the value it drops, in the wording in force.
    const removeLabel = (value: number) =>
      formatMessage(zhCN, 'label.filter.remove-value', {
        value: String(value),
      });
    const ANY_VALUE = new RegExp(
      `^${zhCN['label.filter.remove-value'].replace('{value}', String.raw`\d+`)}$`,
    );
    const removes = () =>
      canvas
        .queryAllByRole('button', { name: ANY_VALUE })
        .map(button => button.getAttribute('aria-label'));
    const remove = (value: number) =>
      canvas.getByRole('button', { name: removeLabel(value) });

    // Three values, which the two boxes of a range could never have held.
    await waitFor(() =>
      expect(removes()).toEqual([100, 1200, 5000].map(removeLabel)),
    );

    // A fourth, entered by hand: Enter commits it and clears the field, and
    // it does not double as the panel's apply. The entry field is named after
    // the field it adds to, which is two catalogue entries deep.
    const entry = canvas.getByLabelText(
      formatMessage(zhCN, 'label.filter.new-value-of', {
        field: formatMessage(zhCN, 'label.filter.value-of', { field: '金额' }),
      }),
    );
    await userEvent.type(entry, '8888{Enter}');
    await waitFor(() => expect(removes()).toContain(removeLabel(8888)));
    await expect(entry).toHaveValue('');

    // And one taken back out, by the button that names it.
    await userEvent.click(remove(1200));
    await waitFor(() =>
      expect(removes()).toEqual([100, 5000, 8888].map(removeLabel)),
    );
  },
};

/**
 * F-08: a condition pill keeps its value control inside its own border, and
 * off the ✕ beside it, at every width the strip is given.
 *
 * The value select asked for `min-w-40`, and 160px is a floor a flex item
 * reports upwards however little room its container has: at 1280 it ran 37px
 * under the remove button and 6px past the pill's own border, at 420 104px
 * and 73px, and the chevron was no longer the element at its own
 * coordinates. Both boxes of a range did the same from the other end — 31px
 * past the ✕ once the separator was between them. Only a real browser lays
 * this out, which is why the rule is measured here rather than asserted as a
 * class name in jsdom.
 */
export const TheValueStaysInsideItsPill: Story = {
  ...DisplayAdvanced,
  args: { instanceId: 'orders-rich', hostWidth: 420 },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    // A saved view opens with its editor folded.
    await userEvent.click(
      await canvas.findByRole('button', {
        name: new RegExp(`^${zhCN['label.filter.panel']}`),
      }),
    );
    const host = canvasElement.querySelector<HTMLElement>('[data-pill-host]')!;

    // It mounts at the narrowest of the three and is widened from there: the
    // view list folds itself away for a narrow column at mount and stays
    // folded, so every step measures a strip and not the fold.
    for (const width of [420, 640, 1024]) {
      host.style.width = `${width}px`;
      await waitFor(() =>
        expect(
          canvasElement.querySelectorAll('[data-slot="filter-condition"]')
            .length,
        ).toBeGreaterThan(0),
      );

      for (const pill of canvasElement.querySelectorAll<HTMLElement>(
        '[data-slot="filter-condition"]',
      )) {
        const frame = pill.getBoundingClientRect();
        const buttons = [...pill.querySelectorAll('button')];
        const cross = buttons[buttons.length - 1].getBoundingClientRect();
        const value = pill.querySelector<HTMLElement>(
          '[data-slot="filter-value"]',
        )!;

        for (const control of value.querySelectorAll<HTMLElement>(
          '[data-slot="select-trigger"], [data-slot="input"], button',
        )) {
          const box = control.getBoundingClientRect();
          // Base UI keeps a hidden input beside the one on screen.
          if (box.width === 0) continue;
          const where = `${pill.getAttribute('aria-label')} @ ${width}`;
          // Inside the pill it belongs to, on both edges.
          await expect(box.right, where).toBeLessThanOrEqual(frame.right);
          await expect(box.left, where).toBeGreaterThanOrEqual(frame.left);
          // And clear of the ✕ wherever the two share a line: a control the
          // remove button covers is a control the pointer cannot reach.
          const sameLine = box.top < cross.bottom && cross.top < box.bottom;
          if (sameLine)
            await expect(box.right, `${where} vs ✕`).toBeLessThanOrEqual(
              cross.left,
            );
        }
      }
    }
  },
};

/**
 * F-09: the calendar speaks the surface's language, and the popover is sized
 * by what is in it rather than by the grid of day numbers at the top.
 *
 * `react-day-picker` reads its words out of a date-fns `Locale` object and
 * falls back to `en-US`, so this story's trigger said «2026年9月15日 –
 * 2026年9月17日» over a popover headed `September 2026` and
 * `Su Mo Tu We Th Fr Sa`. Measured in Chromium: the popover was 212px wide,
 * narrower than its own 235px trigger, 「起始时刻」 was given 48px and broke
 * across two lines in the middle of a word, and the hint under it ran to
 * three.
 */
export const TheCalendarSpeaksTheSurfaceLanguage: Story = {
  ...DisplayWithTime,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await userEvent.click(
      await canvas.findByRole('button', {
        name: new RegExp(`^${zhCN['label.filter.panel']}`),
      }),
    );
    const trigger = await canvas.findByLabelText(
      formatMessage(zhCN, 'label.filter.value-of', { field: '创建时间' }),
    );
    await userEvent.click(trigger);

    const popup = within(document.body);
    const popover = await waitFor(() => {
      const found = document.body.querySelector<HTMLElement>(
        '[data-slot="popover-content"]',
      );
      expect(found).not.toBeNull();
      return found!;
    });
    // A popover appears in two steps, and everything below either measures it
    // or asks whether something inside it is visible — both of which the
    // steps answer wrongly. Base UI keeps the positioner hidden until it has
    // measured where to put the popup, and the popup then fades and scales in
    // (`data-open:fade-in-0 zoom-in-95` through `animate-in`, whose
    // `animation-fill-mode: both` pins the computed opacity at 0 until the
    // first frame). So `toBeVisible` says no, and a box read mid-animation is
    // 5% short — 271px of the 272 it settles at, and 204 at the start. This
    // waits for the whole of it rather than racing it; a fixed delay is a
    // guess a slower machine loses, which is exactly how this passed here and
    // failed on CI.
    await waitFor(() => {
      expect(popover).toBeVisible();
      expect(popover.getAnimations({ subtree: true })).toHaveLength(0);
    });

    // The month and the weekday heads are `Intl` in the surface's language,
    // which is the same call the trigger above was formatted with.
    const september = new Intl.DateTimeFormat('zh-CN', {
      year: 'numeric',
      month: 'long',
    }).format(new Date(2026, 8, 15));
    // Read off the caption rather than found by text: the library also keeps
    // an `aria-live` span for announcing the month, which is empty between
    // announcements and would be the first thing a text query answered with.
    await waitFor(() =>
      expect(
        popover
          .querySelector<HTMLElement>('[class*="month_caption"]')
          ?.textContent?.trim(),
      ).toBe(september),
    );
    await expect(
      [...popover.querySelectorAll('th[aria-label]')].map(head =>
        head.textContent?.trim(),
      ),
    ).toContain(
      new Intl.DateTimeFormat('zh-CN', { weekday: 'short' }).format(
        new Date(2026, 8, 15),
      ),
    );
    // And the words no formatter produces come from the catalogue.
    await expect(
      popup.getByRole('button', { name: zhCN['label.date.calendar-next'] }),
    ).toBeVisible();

    // At least as wide as the control it belongs to: a popover narrower than
    // its own trigger reads as a different, smaller thing.
    await expect(popover.getBoundingClientRect().width).toBeGreaterThanOrEqual(
      trigger.getBoundingClientRect().width,
    );

    // Each field's name on one line. A text node's client rects are one per
    // line it occupies, which is the only way to tell a name that wrapped
    // from one that happened to be tall.
    for (const key of ['label.date.time-from', 'label.date.time-to'] as const) {
      const field = popup.getByLabelText(zhCN[key]);
      const name = popover.querySelector<HTMLElement>(
        `label[for="${field.id}"]`,
      )!;
      const lines = document.createRange();
      lines.selectNodeContents(name);
      await expect(lines.getClientRects().length, zhCN[key]).toBe(1);
      // And the box under it takes the width the name is measured against,
      // rather than whatever a grid of digits left beside it.
      await expect(
        Math.round(field.getBoundingClientRect().width),
      ).toBeGreaterThanOrEqual(Math.round(name.getBoundingClientRect().width));
    }
  },
};

/**
 * A condition on a field whose kind no registry knows (F-06): drawn
 * read-only with the stored value and the reason, its ✕ still working; the
 * condition beside it stays editable; Apply is refused with the count.
 */
export const UnregisteredKind: Story = {
  ...DisplayUnregisteredKind,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const unsupported = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>(
        '[data-slot="filter-unsupported"]',
      );
      if (!found) throw new Error('no read-only condition');
      return found;
    });
    await expect(unsupported).toHaveTextContent('#ff8800');
    await expect(unsupported).toHaveTextContent(
      formatMessage(zhCN, 'label.filter.kind-unregistered', { kind: 'swatch' }),
    );
    // Nothing in it can be typed into; the row can still be taken away.
    await expect(
      unsupported.querySelector('input, [role="combobox"]'),
    ).toBeNull();
    const pill = unsupported.closest<HTMLElement>(
      '[data-slot="filter-condition"]',
    );
    await expect(pill).not.toBeNull();
    await expect(
      within(pill!).getByRole('button', { name: /移除/ }),
    ).toBeVisible();
    // The other condition is an ordinary, editable one.
    const editable = canvasElement.querySelectorAll(
      '[data-slot="filter-condition"] [role="combobox"]',
    );
    await expect(editable.length).toBeGreaterThan(0);
    // Apply is refused, and says how many conditions want fixing.
    await expect(
      canvas.getByRole('button', { name: zhCN['label.filter.apply'] }),
    ).toBeDisabled();
  },
};
