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

import { readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { cleanup, render, renderHook, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import {
  defaultMessages,
  formatIssue,
  formatIssues,
  formatMessage,
  MessagesProvider,
  useViewMessages,
  ViewSurface,
} from '../src/ui/index.js';

afterEach(cleanup);

const src = join(dirname(fileURLToPath(import.meta.url)), '../src');

/** Every issue code raised anywhere in the package, as written in the source. */
function raisedCodes(): string[] {
  const codes = new Set<string>();
  const pattern = /issue\(\s*['`]([a-z][a-z0-9.-]*)['`]/g;

  const walk = (directory: string): void => {
    for (const entry of readdirSync(directory)) {
      const path = join(directory, entry);
      if (statSync(path).isDirectory()) {
        walk(path);
        continue;
      }
      if (!path.endsWith('.ts') && !path.endsWith('.tsx')) continue;
      const source = readFileSync(path, 'utf8');
      for (const match of source.matchAll(pattern)) codes.add(match[1]);
    }
  };

  walk(src);
  return [...codes].sort();
}

describe('the message catalogue', () => {
  it('covers every issue code the package can raise', () => {
    // Without this the code itself reaches the screen, which is how
    // `record.summary.unsupported` ended up in a screenshot.
    const missing = raisedCodes().filter(code => !(code in defaultMessages));

    expect(missing).toEqual([]);
  });

  it('is a sentence, not a key', () => {
    expect(defaultMessages['record.summary.unsupported']).toBe(
      '{field} does not offer the {fn} summary.',
    );
  });
});

describe('formatting', () => {
  it('fills placeholders from the issue params', () => {
    expect(
      formatIssue(defaultMessages, {
        code: 'record.summary.unsupported',
        severity: 'error',
        path: [],
        params: { field: 'Amount', fn: 'AVG' },
      }),
    ).toBe('Amount does not offer the AVG summary.');
  });

  it('falls back to the key, so a gap is visible rather than blank', () => {
    expect(formatMessage(defaultMessages, 'not.a.key')).toBe('not.a.key');
  });

  it('falls back along the dots for a composed code', () => {
    // `/react` composes `<command>.<outcome>`; only the commands, and the
    // few outcomes worth naming, need entries of their own.
    expect(formatMessage(defaultMessages, 'view.save.failed.unavailable')).toBe(
      'This view could not be saved.',
    );
    expect(formatMessage(defaultMessages, 'view.open.failed.not_found')).toBe(
      'This view no longer exists.',
    );
  });

  it('does not borrow a sibling entry for an unknown code', () => {
    expect(formatMessage(defaultMessages, 'record.pageSize.bogus')).toBe(
      'record.pageSize.bogus',
    );
  });

  it('leaves a placeholder alone when the issue carries no such param', () => {
    expect(
      formatIssue(defaultMessages, {
        code: 'record.summary.unsupported',
        severity: 'error',
        path: [],
        params: { field: 'Amount' },
      }),
    ).toBe('Amount does not offer the {fn} summary.');
  });

  it('joins several issues into one line', () => {
    expect(
      formatIssues(defaultMessages, [
        { code: 'filter.value.required', severity: 'error', path: [] },
        { code: 'view.title.empty', severity: 'error', path: [] },
      ]),
    ).toBe('This condition needs a value. A view needs a title.');
  });
});

describe('the provider', () => {
  it('serves the defaults with no provider at all', () => {
    const { result } = renderHook(() => useViewMessages());

    expect(result.current.label('label.query.failed')).toBe('The query failed');
  });

  it('merges an override over the defaults', () => {
    const { result } = renderHook(() => useViewMessages(), {
      wrapper: ({ children }) => (
        <MessagesProvider messages={{ 'label.query.failed': '查询失败' }}>
          {children}
        </MessagesProvider>
      ),
    });

    expect(result.current.label('label.query.failed')).toBe('查询失败');
    // Everything not overridden keeps its default wording.
    expect(result.current.label('label.dashboard.empty')).toBe('No panels yet');
  });

  it('reaches the components through the surface', () => {
    render(
      <ViewSurface messages={{ 'label.dashboard.empty': '还没有面板' }}>
        <Probe />
      </ViewSurface>,
    );

    expect(screen.getByText('还没有面板')).toBeTruthy();
  });
});

function Probe() {
  const messages = useViewMessages();
  return <p>{messages.label('label.dashboard.empty')}</p>;
}
