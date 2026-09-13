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
import { afterEach, expect, it, vi } from 'vitest';
import { FilterOperator } from '@ahoo-wang/fetcher-wow';
import { EditorBoundary } from '../src/filter/FilterEditorSession.js';
import { RecordRendererBoundary } from '../src/record/RecordRendererBoundary.js';
afterEach(cleanup);
it('retries only when a reset key element changes', () => {
  const attempts = vi.fn();
  function Child(): never {
    attempts();
    throw new Error('broken');
  }
  const owner = {};
  const view = render(
    <RecordRendererBoundary label="金额" resetKey={[owner]}>
      <Child />
    </RecordRendererBoundary>,
  );
  const count = attempts.mock.calls.length;
  view.rerender(
    <RecordRendererBoundary label="金额" resetKey={[owner]}>
      <Child />
    </RecordRendererBoundary>,
  );
  expect(attempts).toHaveBeenCalledTimes(count);
  view.rerender(
    <RecordRendererBoundary label="金额" resetKey={[{}]}>
      <Child />
    </RecordRendererBoundary>,
  );
  expect(attempts.mock.calls.length).toBeGreaterThan(count);
  expect(screen.getByRole('alert').textContent).toContain('金额渲染失败');
});
it('isolates editor exceptions even when the error message is empty', () => {
  const onError = vi.fn();
  function Broken(): never {
    throw new Error('');
  }
  expect(() =>
    render(
      <EditorBoundary
        session={{}}
        editor={Broken}
        operator={FilterOperator.MATCH_ALL}
        mode="simple"
        onError={onError}
        onRecover={() => {}}
        onFallback={() => {}}
      >
        <Broken />
      </EditorBoundary>,
    ),
  ).not.toThrow();
  expect(screen.getByRole('button', { name: '使用内置编辑器' })).toBeTruthy();
  expect(onError).toHaveBeenCalled();
});
it('distinguishes a scalar reset identity from a key tuple', () => {
  const attempts = vi.fn();
  function Broken(): never {
    attempts();
    throw new Error('broken');
  }
  const owner = {};
  const view = render(
    <RecordRendererBoundary label="区域" resetKey={owner}>
      <Broken />
    </RecordRendererBoundary>,
  );
  const count = attempts.mock.calls.length;
  view.rerender(
    <RecordRendererBoundary label="区域" resetKey={[owner]}>
      <Broken />
    </RecordRendererBoundary>,
  );
  expect(attempts.mock.calls.length).toBeGreaterThan(count);
});
