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

import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MessagesProvider, zhCN } from '../src/ui/index.js';
import { RecordPagination } from '../src/ui/RecordPagination.js';
import { recordTableController as tableController } from './fixtures/ui.js';

afterEach(cleanup);

/**
 * The count the bar opens with.
 *
 * It answers the question the conditions above it were asked — how many
 * records there are — and not how many of them fitted on the screen. Only
 * when the source gave no total does it fall back to what it can see.
 */
describe('RecordPagination counts the records', () => {
  /**
   * The bar is a landmark with a name of its own (decisions.md D16-5).
   *
   * It borrows `@shadcn/pagination`'s frame for exactly this: it used to be
   * a bare `div`, so the one group of controls that moves a reader through
   * a result was not findable as anything, and the name it now carries
   * comes from the catalogue rather than from the registry's hard-coded
   * English.
   */
  it('is a named navigation landmark', () => {
    render(<RecordPagination table={tableController()} />);

    const bar = screen.getByRole('navigation', { name: 'Pagination' });
    expect(bar.tagName).toBe('NAV');
    expect(bar.dataset.slot).toBe('record-pagination');
  });

  it('takes the landmark name from the catalogue in force', () => {
    render(
      <MessagesProvider messages={zhCN}>
        <RecordPagination table={tableController()} />
      </MessagesProvider>,
    );

    expect(screen.getByRole('navigation', { name: '分页' })).toBeTruthy();
  });

  it('says how many there are in all', () => {
    render(<RecordPagination table={tableController()} />);

    expect(screen.getByText('42 records in all')).toBeTruthy();
    // The rows on this page are not the answer, so they are not also said.
    expect(screen.queryByText(/on this page/)).toBeNull();
  });

  /**
   * One line while there is room for one, two when there is not — and the
   * sentence is never what gives. A row that could not wrap put Next 24px
   * past the result card at a phone's width while `justify-between`
   * squeezed the count into three lines, breaking the Chinese "共 4 条记录"
   * mid-word. The pixels belong to the browser story; the declarations are
   * what jsdom can hold.
   */
  it('wraps rather than overflows, and never breaks the count', () => {
    const { container } = render(
      <RecordPagination table={tableController()} />,
    );

    const bar = container.querySelector<HTMLElement>(
      '[data-slot="record-pagination"]',
    )!;
    expect(bar.className).toContain('flex-wrap');
    expect(screen.getByText('42 records in all').className).toContain(
      'whitespace-nowrap',
    );
    // The controls end the line they land on, first or second.
    expect(bar.lastElementChild!.className).toContain('ml-auto');
  });

  /** A cursor source was never asked for a total, so it claims none. */
  it('says what it can see when the source reports no total', () => {
    render(
      <RecordPagination
        table={tableController({
          paging: { mode: 'paged', index: 1 },
        })}
      />,
    );

    expect(screen.queryByText(/in all/)).toBeNull();
    expect(screen.getByText('2 on this page')).toBeTruthy();
    // Without a total there are no pages to count, only the one reached.
    expect(screen.getByText('Page 1')).toBeTruthy();
  });

  /**
   * The rows on hand are the previous ones while a query is out, so the
   * counts stay with them instead of blanking and snapping back.
   */
  it('keeps the last counts while the next page is loading', () => {
    render(<RecordPagination table={tableController({ status: 'loading' })} />);

    expect(screen.getByText('42 records in all')).toBeTruthy();
    expect(screen.getByText('Page 1 of 3')).toBeTruthy();
  });

  it('renders nothing for a result that is empty and settled', () => {
    const { container } = render(
      <RecordPagination table={tableController({ rows: [] })} />,
    );

    expect(container.firstChild).toBeNull();
  });

  /**
   * The first load has no result behind it, so the bar has nothing true to
   * say: "0 on this page" would count rows that have not arrived, and the
   * `›` beside it would offer a page nobody knows exists.
   */
  it('renders nothing on a first load, where no result has been counted', () => {
    const { container } = render(
      <RecordPagination
        table={tableController({
          rows: [],
          hasResult: false,
          paging: { mode: 'paged', index: 1 },
          status: 'loading',
        })}
      />,
    );

    expect(container.firstChild).toBeNull();
    expect(screen.queryByText('0 on this page')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Next page' })).toBeNull();
  });

  /**
   * And the refresh it must not be mistaken for: the rows on screen are the
   * previous ones, so the counts beside them are the previous result's and
   * true of what the reader is looking at.
   */
  it('keeps the counts while a result already on screen is refreshed', () => {
    render(
      <RecordPagination
        table={tableController({ hasResult: true, status: 'loading' })}
      />,
    );

    expect(screen.getByText('42 records in all')).toBeTruthy();
  });

  /**
   * A later page can come back empty — rows deleted since it was counted, or
   * a source that reports no total answering one page too far. Hiding the bar
   * then takes Previous with it and strands the user on an empty page with
   * nothing to press.
   */
  it('keeps the way back on an empty page that is not the first', () => {
    render(
      <RecordPagination
        table={tableController({
          rows: [],
          paging: { mode: 'paged', index: 3 },
          hasNext: false,
        })}
      />,
    );

    expect(screen.getByText('Page 3')).toBeTruthy();
    expect(
      screen.getByRole('button', { name: 'Previous page' }),
    ).toHaveProperty('disabled', false);
  });

  /** The first page has nowhere to go back to, so an empty one says nothing. */
  it('renders nothing for an empty first page', () => {
    const { container } = render(
      <RecordPagination
        table={tableController({
          rows: [],
          paging: { mode: 'paged', index: 1, total: 0 },
        })}
      />,
    );

    expect(container.firstChild).toBeNull();
  });
});

describe('RecordPagination page size', () => {
  /**
   * What may be offered is the controller's to decide — it is the one that
   * knows the runtime's budget — and the bar draws exactly that list.
   */
  it('offers the sizes the controller allows, each carrying its unit', async () => {
    const user = userEvent.setup();
    render(
      <RecordPagination
        table={tableController({
          pageSize: 25,
          pageSizes: [10, 20, 25, 50],
        })}
      />,
    );

    await user.click(screen.getByRole('combobox', { name: 'Per page' }));
    const options = await screen.findAllByRole('option');
    expect(options.map(option => option.textContent)).toEqual([
      '10 per page',
      '20 per page',
      '25 per page',
      '50 per page',
    ]);
  });

  it('applies the size that was picked', async () => {
    const setPageSize = vi.fn();
    const user = userEvent.setup();
    render(<RecordPagination table={tableController({ setPageSize })} />);

    await user.click(screen.getByRole('combobox', { name: 'Per page' }));
    await user.click(
      await screen.findByRole('option', { name: '50 per page' }),
    );
    expect(setPageSize).toHaveBeenCalledWith(50);
  });

  /**
   * The words in front of the control are its name, rather than a second
   * label kept in step with it by hand.
   */
  it('names the control by the words beside it', () => {
    render(<RecordPagination table={tableController()} />);

    const trigger = screen.getByRole('combobox', { name: 'Per page' });
    const named = document.getElementById(
      trigger.getAttribute('aria-labelledby')!,
    );
    expect(named?.textContent).toBe('Per page');
    expect(trigger.hasAttribute('aria-label')).toBe(false);
  });

  /**
   * The measure word rides with the number in Chinese: `每页` + `20 条`,
   * never `每页 20`, which is what a size spelt into the label would give.
   */
  it('keeps the unit with the number in Chinese', () => {
    render(
      <MessagesProvider messages={zhCN}>
        <RecordPagination table={tableController()} />
      </MessagesProvider>,
    );

    expect(screen.getByText('共 42 条记录')).toBeTruthy();
    expect(screen.getByText('每页')).toBeTruthy();
    expect(
      screen.getByRole('combobox', { name: '每页' }).textContent,
    ).toContain('20 条');
    expect(screen.getByText('第 1 / 3 页')).toBeTruthy();
  });
});

describe('RecordPagination moving between pages', () => {
  it('counts the pages and holds the first one back', () => {
    render(<RecordPagination table={tableController()} />);

    expect(screen.getByText('Page 1 of 3')).toBeTruthy();
    expect(
      screen
        .getByRole('button', { name: 'Previous page' })
        .hasAttribute('disabled'),
    ).toBe(true);
    expect(
      screen
        .getByRole('button', { name: 'Next page' })
        .hasAttribute('disabled'),
    ).toBe(false);
  });

  it('moves either way from a page in the middle', async () => {
    const next = vi.fn();
    const previous = vi.fn();
    const user = userEvent.setup();
    render(
      <RecordPagination
        table={tableController({
          paging: { mode: 'paged', index: 2, total: 42 },
          next,
          previous,
        })}
      />,
    );

    expect(screen.getByText('Page 2 of 3')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Previous page' }));
    await user.click(screen.getByRole('button', { name: 'Next page' }));
    expect(previous).toHaveBeenCalledTimes(1);
    expect(next).toHaveBeenCalledTimes(1);
  });

  it('stops at the last page while the way back stays open', () => {
    render(
      <RecordPagination
        table={tableController({
          paging: { mode: 'paged', index: 3, total: 42 },
          hasNext: false,
        })}
      />,
    );

    expect(screen.getByText('Page 3 of 3')).toBeTruthy();
    expect(
      screen
        .getByRole('button', { name: 'Previous page' })
        .hasAttribute('disabled'),
    ).toBe(false);
    expect(
      screen
        .getByRole('button', { name: 'Next page' })
        .hasAttribute('disabled'),
    ).toBe(true);
  });

  /**
   * Everything fits on one page, so there are no arrows at all (D12). Two
   * dead arrows were the honest version of the same fact, and they still
   * cost two tab stops and a strip of chrome to say "no" — the count and
   * "Page 1 of 1" beside it already say everything is here.
   */
  it('draws no arrows when there is only the one page', () => {
    render(
      <RecordPagination
        table={tableController({
          paging: { mode: 'paged', index: 1, total: 2 },
          hasNext: false,
        })}
      />,
    );

    expect(screen.getByText('2 records in all')).toBeTruthy();
    expect(screen.getByText('Page 1 of 1')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Previous page' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Next page' })).toBeNull();
    // The size control stays: how many rows a page holds is what makes it
    // one page, and it is the one thing still worth changing here.
    expect(screen.getByRole('combobox', { name: 'Per page' })).toBeTruthy();
  });

  /**
   * And the two it must not be mistaken for. A source that reports no total
   * cannot tell one page from the first of many, and a reader stranded past
   * the first page needs the way back whatever the total claims.
   */
  it('keeps the arrows where a single page cannot be proved', () => {
    render(
      <RecordPagination
        table={tableController({ paging: { mode: 'paged', index: 1 } })}
      />,
    );
    expect(screen.getByRole('button', { name: 'Next page' })).toBeTruthy();

    cleanup();
    render(
      <RecordPagination
        table={tableController({
          rows: [],
          paging: { mode: 'paged', index: 2, total: 2 },
          hasNext: false,
        })}
      />,
    );
    expect(
      screen.getByRole('button', { name: 'Previous page' }),
    ).toHaveProperty('disabled', false);
  });

  /**
   * A cursor source can only go forward, and knows of no page numbers at
   * all, so it shows neither rather than showing them dead.
   */
  it('shows a cursor source only the way forward', () => {
    render(
      <RecordPagination
        table={tableController({
          paging: { mode: 'cursor', nextCursor: 'c-2' },
        })}
      />,
    );

    expect(screen.queryByText(/Page/)).toBeNull();
    expect(screen.queryByText(/in all/)).toBeNull();
    expect(screen.getByText('2 on this page')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Previous page' })).toBeNull();
    expect(
      screen
        .getByRole('button', { name: 'Next page' })
        .hasAttribute('disabled'),
    ).toBe(false);
    // The size control is the one thing a cursor source keeps.
    expect(screen.getByRole('combobox', { name: 'Per page' })).toBeTruthy();
  });

  it('stops at the end of a cursor source', () => {
    render(
      <RecordPagination
        table={tableController({
          paging: { mode: 'cursor', nextCursor: null },
          hasNext: false,
        })}
      />,
    );

    expect(
      screen
        .getByRole('button', { name: 'Next page' })
        .hasAttribute('disabled'),
    ).toBe(true);
  });

  /**
   * The bar is walked in the order it reads: the size first, then the page
   * to jump to, then the two steps out of this one. Nothing before the size
   * control takes focus, because the count is a sentence and not a control.
   */
  it('takes focus in the order it is read', async () => {
    const user = userEvent.setup();
    render(
      <RecordPagination
        table={tableController({
          paging: { mode: 'paged', index: 2, total: 42 },
        })}
      />,
    );

    await user.tab();
    expect(document.activeElement).toBe(
      screen.getByRole('combobox', { name: 'Per page' }),
    );
    await user.tab();
    expect(document.activeElement).toBe(
      screen.getByRole('textbox', { name: 'Go to page' }),
    );
    await user.tab();
    expect(document.activeElement).toBe(
      screen.getByRole('button', { name: 'Previous page' }),
    );
    await user.tab();
    expect(document.activeElement).toBe(
      screen.getByRole('button', { name: 'Next page' }),
    );
  });
});

/**
 * Page 17 of 40, said outright rather than stepped to (D18 ruling Ⅷ).
 *
 * The box exists only where there is an M to check an answer against, and
 * only where the two steps are drawn at all: a result that fits on one page
 * has nowhere to send anybody.
 */
describe('RecordPagination jumping to a page', () => {
  const paged = (overrides = {}) =>
    tableController({
      paging: { mode: 'paged', index: 2, total: 42 },
      ...overrides,
    });

  it('is a named box beside the page it is on', () => {
    render(<RecordPagination table={paged()} />);

    const box = screen.getByRole('textbox', { name: 'Go to page' });
    expect(box.getAttribute('inputmode')).toBe('numeric');
    // The sentence says where the rows came from; the box says where to go,
    // and starts from where the reader is.
    expect(screen.getByText('Page 2 of 3')).toBeTruthy();
    expect((box as HTMLInputElement).value).toBe('2');
  });

  it('is named by the catalogue in force', () => {
    render(
      <MessagesProvider messages={zhCN}>
        <RecordPagination table={paged()} />
      </MessagesProvider>,
    );

    expect(screen.getByRole('textbox', { name: '跳到第…页' })).toBeTruthy();
  });

  it('goes to the page that was typed, on Enter', async () => {
    const goTo = vi.fn();
    const user = userEvent.setup();
    render(<RecordPagination table={paged({ goTo })} />);

    const box = screen.getByRole('textbox', { name: 'Go to page' });
    await user.clear(box);
    await user.type(box, '3{Enter}');
    expect(goTo).toHaveBeenCalledWith(3);
  });

  it('goes to the page that was typed, on leaving the box', async () => {
    const goTo = vi.fn();
    const user = userEvent.setup();
    render(<RecordPagination table={paged({ goTo })} />);

    const box = screen.getByRole('textbox', { name: 'Go to page' });
    await user.clear(box);
    await user.type(box, '1');
    await user.tab();
    expect(goTo).toHaveBeenCalledWith(1);
  });

  /** Past the end the reader means the end, so it is clamped, not refused. */
  it('clamps to the pages there are', async () => {
    const goTo = vi.fn();
    const user = userEvent.setup();
    render(<RecordPagination table={paged({ goTo })} />);

    const box = screen.getByRole('textbox', { name: 'Go to page' });
    await user.clear(box);
    await user.type(box, '99{Enter}');
    expect(goTo).toHaveBeenCalledWith(3);
    expect((box as HTMLInputElement).value).toBe('3');

    await user.clear(box);
    await user.type(box, '0{Enter}');
    expect(goTo).toHaveBeenLastCalledWith(1);
  });

  /**
   * Anything that is not a page number is not a question, so the box goes
   * back to saying where the reader is rather than guessing. A blank box is
   * the one that matters: `Number('')` is 0, and a clamp alone would read an
   * emptied box as "take me to the first page".
   */
  it.each(['', 'abc', '1.5', '-2', '1e3'])(
    'ignores %o and says where the reader is',
    async typed => {
      const goTo = vi.fn();
      const user = userEvent.setup();
      render(<RecordPagination table={paged({ goTo })} />);

      const box = screen.getByRole('textbox', { name: 'Go to page' });
      await user.clear(box);
      if (typed !== '') await user.type(box, typed);
      await user.tab();
      expect(goTo).not.toHaveBeenCalled();
      expect((box as HTMLInputElement).value).toBe('2');
    },
  );

  /** The page already on screen costs a query and answers nothing. */
  it('does not ask again for the page it is on', async () => {
    const goTo = vi.fn();
    const user = userEvent.setup();
    render(<RecordPagination table={paged({ goTo })} />);

    const box = screen.getByRole('textbox', { name: 'Go to page' });
    await user.click(box);
    await user.tab();
    expect(goTo).not.toHaveBeenCalled();
  });

  /** A cursor source has no M, so there is nothing to check an answer by. */
  it('is not drawn for a source that reports no total', () => {
    render(
      <RecordPagination
        table={tableController({ paging: { mode: 'cursor', nextCursor: 'c' } })}
      />,
    );

    expect(screen.queryByRole('textbox', { name: 'Go to page' })).toBeNull();
  });

  it('is not drawn for a paged source that reports no total', () => {
    render(
      <RecordPagination
        table={tableController({ paging: { mode: 'paged', index: 1 } })}
      />,
    );

    expect(screen.getByText('Page 1')).toBeTruthy();
    expect(screen.queryByRole('textbox', { name: 'Go to page' })).toBeNull();
  });

  /** Everything fits: no steps are drawn (D12 Ⅶ), and no box either. */
  it('is not drawn when there is only the one page', () => {
    render(
      <RecordPagination
        table={tableController({
          paging: { mode: 'paged', index: 1, total: 4 },
          hasNext: false,
        })}
      />,
    );

    expect(screen.queryByRole('textbox', { name: 'Go to page' })).toBeNull();
  });

  /** A page that landed is the truth about where the reader is. */
  it('follows the page that landed', async () => {
    const user = userEvent.setup();
    const { rerender } = render(<RecordPagination table={paged()} />);

    const box = screen.getByRole('textbox', { name: 'Go to page' });
    await user.clear(box);
    await user.type(box, '3');
    expect((box as HTMLInputElement).value).toBe('3');

    rerender(
      <RecordPagination
        table={tableController({
          paging: { mode: 'paged', index: 1, total: 42 },
        })}
      />,
    );
    expect(
      (screen.getByRole('textbox', { name: 'Go to page' }) as HTMLInputElement)
        .value,
    ).toBe('1');
  });
});
