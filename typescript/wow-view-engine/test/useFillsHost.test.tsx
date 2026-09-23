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
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render } from '@testing-library/react';
import { useRef } from 'react';
import { FILLS_HOST, useFillsHost } from '../src/ui/workbench/useFillsHost.js';

/**
 * jsdom lays nothing out, so the two heights the hook compares are given:
 * the root's as the host has it, and the root's once it is sized by its
 * content alone — which is what it is while its inline height is `auto`.
 */
function Root({ given, content }: { given: number; content: number }) {
  const ref = useRef<HTMLDivElement>(null);
  useFillsHost(ref);
  return (
    <div>
      <div
        ref={node => {
          ref.current = node;
          if (!node) return;
          node.getBoundingClientRect = () =>
            ({
              height: node.style.height === 'auto' ? content : given,
            }) as DOMRect;
        }}
        data-testid="root"
      />
    </div>
  );
}

afterEach(cleanup);

describe('useFillsHost', () => {
  it('marks a root whose host gave it a height other than its content', () => {
    const { getByTestId } = render(<Root given={640} content={300} />);
    expect(getByTestId('root').hasAttribute(FILLS_HOST)).toBe(true);
  });

  it('leaves a root sized by its content in page flow', () => {
    const { getByTestId } = render(<Root given={300} content={300} />);
    expect(getByTestId('root').hasAttribute(FILLS_HOST)).toBe(false);
  });

  it('puts back the inline style it borrowed for the reading', () => {
    const { getByTestId } = render(<Root given={640} content={300} />);
    const root = getByTestId('root');
    expect(root.style.height).toBe('');
    expect(root.style.alignSelf).toBe('');
    expect(root.style.flex).toBe('');
  });
});
