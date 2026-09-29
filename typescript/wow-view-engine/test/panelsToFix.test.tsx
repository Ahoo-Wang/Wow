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

import { renderHook } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { Issue } from '../src/index.js';
import { ViewSurface, zhCN } from '../src/ui/index.js';
import { useViewMessages } from '../src/ui/MessagesProvider.js';
import { boardErrorTitle, panelsToFix } from '../src/ui/panelsToFix.js';

const error = (path: Issue['path']): Issue => ({
  code: 'dashboard.panel.failed',
  severity: 'error',
  path,
});

/**
 * The red line over a board whose only trouble is its panels talks about
 * the panels (todo 5, 2026-09-28): they are out and the rest draws, so
 * 「这个仪表盘要先修正才能运行」 said something untrue of the board.
 */
describe('the error line over a board', () => {
  it('counts the panels when every error is a panel’s own', () => {
    expect(
      panelsToFix([
        error(['panels', 0, 'view']),
        error(['panels', 0, 'bindings', 1]),
        error(['panels', 2]),
      ]),
    ).toBe(2);
    // A board a definition declares: its panels are under its view, and
    // the board open says the same panel again at its own path.
    expect(
      panelsToFix([
        error(['views', 0, 'config', 'panels', 1, 'view']),
        error(['views', 0, 'config', 'panels', 2, 'bindings', 0]),
        error(['panels', 1]),
      ]),
    ).toBe(2);
    // A warning is no error.
    expect(
      panelsToFix([
        error(['panels', 0]),
        { ...error(['fields', 0]), severity: 'warning' },
      ]),
    ).toBe(1);
  });

  it('counts none when any error is the board’s own', () => {
    expect(panelsToFix([])).toBe(0);
    expect(panelsToFix([error(['panels', 0]), error(['fields', 0])])).toBe(0);
    // "Too many panels" belongs to no one panel.
    expect(panelsToFix([error(['panels'])])).toBe(0);
    expect(panelsToFix([error(['views', 0, 'config', 'fields', 0])])).toBe(0);
  });

  it('heads the line with the panels, or with the board, in both catalogues', () => {
    const en = renderHook(() => useViewMessages()).result.current;
    const zh = renderHook(() => useViewMessages(), {
      wrapper: ({ children }) => (
        <ViewSurface messages={zhCN} locale="zh-CN">
          {children}
        </ViewSurface>
      ),
    }).result.current;
    const two = [error(['panels', 0]), error(['panels', 1])];
    const one = [error(['panels', 0]), error(['panels', 0, 'view'])];
    const board = [error(['panels', 0]), error(['fields', 0])];

    expect(boardErrorTitle(two, zh)).toBe('有 2 个面板要先修正才能显示');
    expect(boardErrorTitle(one, zh)).toBe('有 1 个面板要先修正才能显示');
    expect(boardErrorTitle(board, zh)).toBe('这个仪表盘要先修正才能运行');
    expect(boardErrorTitle(two, en)).toBe(
      '2 panels need fixing before they can show',
    );
    expect(boardErrorTitle(one, en)).toBe(
      '1 panel needs fixing before it can show',
    );
    expect(boardErrorTitle(board, en)).toBe(
      'This dashboard needs fixing before it runs',
    );
  });
});
