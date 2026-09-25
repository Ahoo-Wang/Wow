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

import { combineURLs } from '@ahoo-wang/fetcher';
import { describe, expect, it } from 'vitest';
import {
  boundedContextFilePath,
  combinePaths,
  modelFilePath,
} from '../../src/naming/paths';

describe('combinePaths', () => {
  // It replaced fetcher's combineURLs (refactor batch B6), so the generator
  // process no longer loads fetcher; it must join every path the same way.
  it.each([
    ['/out', 'shop/types.ts', '/out/shop/types.ts'],
    ['/out/', '/shop/types.ts', '/out/shop/types.ts'],
    ['out//', '/types.ts', 'out/types.ts'],
    ['/', 'types.ts', '/types.ts'],
    ['shop', '/', 'shop/'],
    ['shop', '', 'shop'],
    ['', 'types.ts', '/types.ts'],
    ['/out', 'https://example.com/a', 'https://example.com/a'],
    ['/out', '//host/a', '//host/a'],
    ['/out', 'C:/a', '/out/C:/a'],
  ])(
    'joins %j and %j into %j, as combineURLs does',
    (base, relative, joined) => {
      expect(combinePaths(base, relative)).toBe(joined);
      expect(combineURLs(base, relative)).toBe(joined);
    },
  );
});

describe('file layout', () => {
  it('declares the models of a package in its types.ts', () => {
    expect(modelFilePath({ path: 'models' })).toBe('models/types.ts');
    expect(modelFilePath({ path: '/com/example' })).toBe(
      '/com/example/types.ts',
    );
    expect(modelFilePath({ path: '/' })).toBe('/types.ts');
  });

  it("declares a bounded context's alias in the context's boundedContext.ts", () => {
    expect(boundedContextFilePath('shop')).toBe('shop/boundedContext.ts');
  });
});
