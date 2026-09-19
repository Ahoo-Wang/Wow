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

import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Issue } from '../src/index.js';
import { Button } from '../src/ui/components/button.js';
import { dedupeIssues, StatusStrip } from '../src/ui/StatusStrip.js';

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

    // The theme's own `warning` token, where destructive has one of its own.
    expect(strip().getAttribute('data-tone')).toBe('warning');
    expect(strip().className).toContain('border-warning');
    expect(strip().className).toContain('mt-2');

    rerender(<StatusStrip tone="error" title="It broke" />);
    expect(strip().className).toContain('border-destructive');

    rerender(<StatusStrip tone="info" title="Stale" />);
    expect(strip().className).toContain('text-muted-foreground');
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
