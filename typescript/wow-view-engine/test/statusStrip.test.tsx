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
  cleanup,
  fireEvent,
  render,
  screen,
  within,
} from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { FilterTree, FilterValue, Issue } from '../src/index.js';
import { Button } from '../src/ui/components/button.js';
import { dedupeIssues, NoteStrip, StatusStrip } from '../src/ui/StatusStrip.js';
// `unmarkedErrors` is a question about the tree, so it moved to the filter
// kernel; the strip beside it only renders what it is handed.
import { unmarkedErrors } from '../src/filter/index.js';
import {
  ErrorStrip,
  MessagesProvider,
  QueryStrip,
  WarningStrip,
} from '../src/ui/index.js';
import { zhCN } from '../src/ui/messages/zh-CN.js';

afterEach(cleanup);

describe('StatusStrip', () => {
  it('is announced as an alert only when something stopped the view', () => {
    const { rerender } = render(<StatusStrip tone="error" title="It broke" />);
    // An error stopped the query; a screen reader should interrupt for it.
    expect(screen.getByRole('alert').textContent).toContain('It broke');

    rerender(<StatusStrip tone="warning" title="Worth noting" />);
    expect(screen.getByRole('status').textContent).toContain('Worth noting');

    rerender(<StatusStrip tone="info" title="Stale" />);
    expect(screen.getByRole('status').textContent).toContain('Stale');
  });

  it('wears the colour of its tone and says which it is', () => {
    const { rerender } = render(
      <StatusStrip tone="warning" title="Worth noting" className="mt-2" />,
    );
    const strip = () =>
      document.querySelector('[data-slot="status-strip"]') as HTMLElement;

    // Which tone it is, said on the element — the tone decides the colour,
    // the icon and the role it is announced with, and `LineAlert` holds the
    // one recipe for all three. `mt-2` is not a colour but the caller's own
    // class, and that it survives is the contract being checked.
    expect(strip().getAttribute('data-tone')).toBe('warning');
    expect(strip().className).toContain('mt-2');

    rerender(<StatusStrip tone="error" title="It broke" />);
    expect(strip().getAttribute('data-tone')).toBe('error');

    rerender(<StatusStrip tone="info" title="Stale" />);
    expect(strip().getAttribute('data-tone')).toBe('info');
  });

  it('stays one line when there is nothing behind the sentence', () => {
    render(<StatusStrip tone="warning" title="Worth noting" />);

    // No details, no fold: a toggle that expands nothing is a lie.
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('folds its findings behind a count until they are asked for', () => {
    render(
      <StatusStrip
        tone="warning"
        title="2 things worth noting"
        details={['The filter opened in the advanced editor', 'No AVG here']}
      />,
    );

    // The result below is what the user came for; a stack of sentences
    // pushes it off the screen.
    expect(screen.queryByText('No AVG here')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '2 more' }));

    expect(screen.getByText('No AVG here')).toBeDefined();
    expect(
      screen.getByText('The filter opened in the advanced editor'),
    ).toBeDefined();
  });

  /**
   * "{count} more" beside findings that are already on screen points at what
   * the reader can see and calls it withheld. The toggle folds them away, so
   * once it is open it says that instead.
   */
  it('says what the press does next, not what it did last', () => {
    render(
      <StatusStrip
        tone="warning"
        title="1 thing worth noting"
        details={['No AVG here']}
      />,
    );

    const toggle = () => screen.getByRole('button');
    expect(toggle().textContent).toBe('1 more');
    expect(toggle().getAttribute('aria-expanded')).toBe('false');

    fireEvent.click(toggle());

    expect(toggle().getAttribute('aria-expanded')).toBe('true');
    expect(toggle().textContent).toBe('Show less');

    fireEvent.click(toggle());

    expect(toggle().textContent).toBe('1 more');
  });

  it('carries what to do about it at the end of the line', () => {
    const retry = vi.fn();
    render(
      <StatusStrip
        tone="error"
        title="The query failed"
        action={<Button onClick={retry}>{'Try again'}</Button>}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));

    expect(retry).toHaveBeenCalledTimes(1);
  });
});

describe('NoteStrip', () => {
  const note: Issue = {
    code: 'analysis.result.more-groups',
    severity: 'note',
    path: ['limit'],
    params: { limit: 30 },
  };

  /**
   * A view that asked for its top 30 was answered as asked: the groups left
   * out are said as a note, in the quiet tone, and never as a warning.
   */
  it('says a note quietly, and a warning strip does not take it', () => {
    const { container } = render(<WarningStrip issues={[note]} />);
    expect(container.innerHTML).toBe('');
    cleanup();

    render(<NoteStrip issues={[note]} />);
    const line = screen.getByRole('status');
    expect(line.getAttribute('data-tone')).toBe('info');
    expect(line.textContent).toContain('30');
  });
});

describe('dedupeIssues', () => {
  const notSimple: Issue = {
    code: 'config.filterMode.not-simple',
    severity: 'warning',
    path: [],
  };

  /**
   * A dashboard validates a global condition once as its own and once per
   * panel it maps onto, so the same sentence arrives with several paths. The
   * code and the params are the sentence; the path is not.
   */
  it('keeps one of each sentence, however many paths raise it', () => {
    const kept = dedupeIssues([
      notSimple,
      { ...notSimple, path: ['panels', 0, 'filterMode'] },
    ]);

    expect(kept).toEqual([notSimple]);
  });

  it('tells two findings of one code apart by their params', () => {
    const avg: Issue = {
      code: 'record.summary.unsupported',
      severity: 'warning',
      path: ['summaries', 0],
      params: { field: 'Amount', fn: 'AVG' },
    };
    const sum: Issue = { ...avg, path: ['summaries', 1] };
    sum.params = { field: 'Amount', fn: 'SUM' };

    expect(dedupeIssues([avg, sum, { ...avg, path: [] }])).toEqual([avg, sum]);
  });

  it('counts a finding with no params apart from one that has them', () => {
    const withParams: Issue = { ...notSimple, params: { field: 'Amount' } };

    expect(dedupeIssues([notSimple, withParams])).toHaveLength(2);
    // Either way round: the comparison is of two sentences, not of one
    // sentence against whatever the other one happens to carry.
    expect(dedupeIssues([withParams, notSimple])).toHaveLength(2);
  });
});

describe('WarningStrip', () => {
  it('renders nothing when there is no warning to report', () => {
    const { container } = render(
      <WarningStrip issues={[{ code: 'x', severity: 'error', path: [] }]} />,
    );

    expect(container.innerHTML).toBe('');
  });

  it('wears the warning colour, a class of its own, and a status role', () => {
    render(
      <WarningStrip
        className="mt-2"
        issues={[
          { code: 'blocking.elsewhere', severity: 'error', path: [] },
          {
            code: 'config.filterMode.not-simple',
            severity: 'warning',
            path: ['filterMode'],
          },
        ]}
      />,
    );

    // A status, not an alert: a screen reader mentions it without
    // interrupting whatever its user was doing.
    const notice = screen.getByRole('status');
    expect(notice.getAttribute('data-tone')).toBe('warning');
    expect(notice.className).toContain('mt-2');
    // One finding is its own sentence: no count to read, nothing to unfold.
    expect(notice.textContent).toContain('advanced editor');
    expect(notice.textContent).not.toContain('worth noting');
    // Only the warnings; the error has a strip of its own elsewhere.
    expect(notice.textContent).not.toContain('blocking.elsewhere');
    expect(screen.queryByRole('button', { name: /more/ })).toBeNull();
  });

  /**
   * A dashboard validates a global condition once as its own and once per
   * panel it maps onto, so the same sentence arrived twice with two paths.
   * The code and the params are the sentence; one of each is said.
   */
  it('says the same sentence once, however many paths raise it', () => {
    render(
      <WarningStrip
        issues={[
          {
            code: 'config.filterMode.not-simple',
            severity: 'warning',
            path: [],
          },
          {
            code: 'config.filterMode.not-simple',
            severity: 'warning',
            path: ['panels', 0, 'filterMode'],
          },
          {
            code: 'record.summary.unsupported',
            severity: 'warning',
            path: ['summaries', 0],
            params: { field: 'Amount', fn: 'AVG' },
          },
          {
            code: 'record.summary.unsupported',
            severity: 'warning',
            path: ['summaries', 1],
            params: { field: 'Amount', fn: 'SUM' },
          },
        ]}
      />,
    );

    // Three sentences behind one line, which says how many there are.
    expect(screen.getByRole('status').textContent).toContain(
      '3 things worth noting',
    );
    fireEvent.click(screen.getByRole('button', { name: '3 more' }));

    const text = screen.getByRole('status').textContent ?? '';
    expect(text.match(/advanced editor/g)).toHaveLength(1);
    expect(text).toContain('AVG summary');
    expect(text).toContain('SUM summary');
  });
});

describe('ErrorStrip', () => {
  /** A draft holding two conditions, which is what the editor draws pills for. */
  const twoConditions: FilterTree = {
    op: 'and',
    children: [
      { field: 'warehouse', operator: 'EQ', value: 'CN' },
      { field: 'status', operator: 'EQ', value: 'PENDING' },
    ],
  };

  it('leaves the conditions the editor marks to the editor', () => {
    // A wrong condition is marked on its own pill and counted on Apply,
    // which is where it can be fixed; the strip would only say it again.
    const marked = unmarkedErrors(
      [
        {
          code: 'filter.value.expected-date',
          severity: 'error',
          path: ['children', 0],
        },
        {
          code: 'record.column.unknown',
          severity: 'error',
          path: ['table', 'columns', 0],
          params: { field: 'gone' },
        },
        {
          code: 'config.filterMode.not-simple',
          severity: 'warning',
          path: [],
        },
      ],
      twoConditions,
    );

    expect(marked.map(found => found.code)).toEqual(['record.column.unknown']);

    render(<ErrorStrip issues={marked} />);
    const strip = screen.getByRole('alert');
    expect(strip.dataset.tone).toBe('error');
    // The one that is left is said outright, not headed and folded away.
    expect(strip.textContent).toContain('record.column.unknown');
  });

  /**
   * One finding is its own sentence (F-14). "This view needs fixing" over a
   * fold reading "1 more" was a heading with one thing under it, and the one
   * thing it hid was the only sentence that said what to fix.
   */
  it('says a single error outright, with no fold over it', () => {
    render(
      <ErrorStrip
        issues={[
          {
            code: 'record.pageSize.not-positive',
            severity: 'error',
            path: ['pageSize'],
          },
        ]}
      />,
    );

    const strip = screen.getByRole('alert');
    expect(strip.textContent).toContain(
      'The page size must be a positive number.',
    );
    expect(strip.textContent).not.toContain('needs fixing');
    expect(within(strip).queryByRole('button', { name: '1 more' })).toBeNull();
  });

  /** Two of them are a count over a fold; the line has room for one. */
  it('heads several errors and folds them behind the count', () => {
    render(
      <ErrorStrip
        issues={[
          {
            code: 'record.pageSize.not-positive',
            severity: 'error',
            path: ['pageSize'],
          },
          {
            code: 'record.pageSize.too-large',
            severity: 'error',
            path: ['pageSize'],
            params: { max: 100 },
          },
        ]}
      />,
    );

    const strip = screen.getByRole('alert');
    expect(strip.textContent).toContain('needs fixing');
    fireEvent.click(within(strip).getByRole('button', { name: '2 more' }));
    expect(strip.textContent).toContain(
      'The page size must be a positive number.',
    );
    expect(strip.textContent).toContain('The page size cannot exceed 100.');
  });

  /** The way out of it, which only the surface around it knows. */
  it('carries the action it was handed at the end of the line', () => {
    render(
      <ErrorStrip
        issues={[
          {
            code: 'record.pageSize.not-positive',
            severity: 'error',
            path: ['pageSize'],
          },
        ]}
        action={<Button>Open column settings</Button>}
      />,
    );

    const strip = screen.getByRole('alert');
    expect(
      within(strip).getByRole('button', { name: 'Open column settings' }),
    ).toBeTruthy();
  });

  it('says nothing when every error is marked elsewhere', () => {
    const { container } = render(
      <ErrorStrip
        issues={unmarkedErrors(
          [
            {
              code: 'filter.value.expected-text',
              severity: 'error',
              path: ['children', 1],
            },
          ],
          twoConditions,
        )}
      />,
    );

    expect(container.innerHTML).toBe('');
  });

  /**
   * A condition-shaped path is not the same thing as a pill. The panel skips
   * a malformed child — there is nothing to draw a field, an operator or a
   * value editor from — so a finding about it is marked nowhere, and the
   * strip is the only place it can ever be read.
   */
  it('keeps an error about a node the editor cannot draw', () => {
    const kept = unmarkedErrors(
      [
        {
          code: 'filter.node.invalid',
          severity: 'error',
          path: ['children', 0],
        },
      ],
      { op: 'and', children: [null as unknown as FilterTree] },
    );

    expect(kept.map(found => found.path)).toEqual([['children', 0]]);
  });

  /** A group carries no marker of its own; only its conditions do. */
  it('keeps an error about a group, which wears no mark', () => {
    const kept = unmarkedErrors(
      [
        {
          code: 'filter.group.duplicate-field',
          severity: 'error',
          path: ['children', 0],
          params: { field: 'warehouse' },
        },
      ],
      { op: 'and', children: [{ op: 'or', children: [] }] },
    );

    expect(kept).toHaveLength(1);
  });

  /**
   * A condition inside an element match is drawn by the same components,
   * from the tree the leaf carries, so it wears a pill like any other.
   */
  it('leaves a condition inside a predicate to the editor', () => {
    const kept = unmarkedErrors(
      [
        {
          code: 'filter.value.expected-text',
          severity: 'error',
          path: ['children', 0, 'children', 1],
        },
      ],
      {
        op: 'and',
        children: [
          {
            field: 'lines',
            operator: 'ELEMENT_MATCH',
            value: {
              op: 'and',
              children: [
                { field: 'sku', operator: 'EQ', value: 'a' },
                { field: 'qty', operator: 'EQ', value: 1 },
              ],
            } as unknown as FilterValue,
          },
        ],
      },
    );

    expect(kept).toEqual([]);
  });
});

describe('QueryStrip', () => {
  const failure: Issue = {
    code: 'label.query.failed',
    severity: 'error',
    path: [],
  };

  /**
   * The rows on screen are not the answer to what was asked: that is the
   * sentence the reader needs most, so it is on the failure's own line, not
   * behind a 「还有 1 项」 fold the rows would be read as current under.
   */
  it('says on the failure line itself that the rows are the last answer', () => {
    render(
      <MessagesProvider messages={zhCN}>
        <QueryStrip error={failure} stale onRetry={() => {}} />
      </MessagesProvider>,
    );

    const line = screen.getByRole('alert');
    expect(line.textContent).toContain('查询失败 · 显示的是上一次成功的结果');
    // Nothing is folded: the one thing it had to add is already said.
    expect(within(line).queryByRole('button', { name: /还有/ })).toBeNull();
    expect(within(line).getByRole('button', { name: '重试' })).toBeTruthy();
  });

  it('says the failure alone when nothing came back before it', () => {
    render(<QueryStrip error={failure} stale={false} />);

    const line = screen.getByRole('alert');
    expect(line.textContent).toBe('The query failed');
    expect(within(line).queryAllByRole('button')).toHaveLength(0);
  });

  it('says nothing while the query has not failed', () => {
    const { container } = render(<QueryStrip error={null} stale />);

    expect(container.textContent).toBe('');
  });
});
