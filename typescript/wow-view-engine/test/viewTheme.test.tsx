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

import { createRef } from 'react';
import { cleanup, render } from '@testing-library/react';
import { afterEach, expect, it } from 'vitest';
import { ViewTheme, type ViewThemeStyle } from '../src/theme/ViewTheme.js';
afterEach(cleanup);
it('keeps arbitrary user themes, overrides and nested inheritance on the actual scope', () => {
  const ref = createRef<HTMLDivElement>();
  const style: ViewThemeStyle = { '--fve-primary': '#123456' };
  const view = render(
    <ViewTheme
      ref={ref}
      theme="brand"
      appearance="dark"
      density="compact"
      style={style}
      data-fve-theme="ignored"
    >
      <ViewTheme data-testid="nested" />
    </ViewTheme>,
  );
  expect(ref.current?.getAttribute('data-fve-theme')).toBe('brand');
  expect(ref.current?.style.getPropertyValue('--fve-primary')).toBe('#123456');
  expect(view.getByTestId('nested').hasAttribute('data-theme')).toBe(false);
  expect(view.getByTestId('nested').hasAttribute('data-fve-density')).toBe(
    false,
  );
  view.rerender(
    <ViewTheme ref={ref} data-fve-theme="raw" appearance="system" />,
  );
  expect(ref.current?.getAttribute('data-fve-theme')).toBe('raw');
  expect(ref.current?.getAttribute('data-theme')).toBe('system');
  expect(ref.current?.style.getPropertyValue('--fve-primary')).toBe('');
});

it('removes mapped typography when overrides are withdrawn and preserves explicit CSS styles', () => {
  const ref = createRef<HTMLDivElement>();
  const view = render(
    <ViewTheme>
      <ViewTheme
        ref={ref}
        style={{
          '--fve-font-family': 'monospace',
          '--fve-font-size': '20px',
          '--fve-line-height': 2,
          fontFamily: 'serif',
        }}
      />
    </ViewTheme>,
  );
  expect(ref.current?.style.fontFamily).toBe('serif');
  expect(ref.current?.style.fontSize).toBe('var(--fve-font-size)');
  expect(ref.current?.style.lineHeight).toBe('var(--fve-line-height)');
  view.rerender(
    <ViewTheme>
      <ViewTheme
        ref={ref}
        style={{ '--fve-font-size': '20px', fontSize: undefined }}
      />
    </ViewTheme>,
  );
  expect(ref.current?.style.fontSize).toBe('var(--fve-font-size)');
  view.rerender(
    <ViewTheme>
      <ViewTheme ref={ref} />
    </ViewTheme>,
  );
  expect(ref.current?.style.fontFamily).toBe('');
  expect(ref.current?.style.fontSize).toBe('');
  expect(ref.current?.style.lineHeight).toBe('');
});
