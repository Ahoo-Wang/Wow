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

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook } from '@testing-library/react';
import { useFetcherSingleQuery } from '../../src/wow/useFetcherSingleQuery';
import {
  SingleQuery,
  SortDirection,
  Operator,
  eq,
  contains,
} from '@ahoo-wang/fetcher-wow';

// Mock the useFetcherQuery hook
vi.mock('../../src/wow/useFetcherQuery', () => ({
  useFetcherQuery: vi.fn(),
}));

import { useFetcherQuery } from '../../src/fetcher/useFetcherQuery';

describe('useFetcherSingleQuery', () => {
  const mockSingleQuery: SingleQuery<string> = {
    condition: eq('id', '123'),
    sort: [{ field: 'createdAt', direction: SortDirection.DESC }],
  };

  const mockQueryReturn = {
    loading: false,
    result: null,
    error: null,
    status: 'idle',
    execute: vi.fn(),
    reset: vi.fn(),
    abort: vi.fn(),
    getQuery: vi.fn(),
    setQuery: vi.fn(),
  };

  beforeEach(() => {
    vi.clearAllMocks();
    (useFetcherQuery as any).mockReturnValue(mockQueryReturn);
  });

  it('should call useFetcherQuery with correct parameters', () => {
    const options = {
      url: '/api/single',
      initialQuery: mockSingleQuery,
      autoExecute: true,
    };

    renderHook(() => useFetcherSingleQuery<any>(options));

    expect(useFetcherQuery).toHaveBeenCalledWith(options);
  });

  it('should return the same interface as useFetcherQuery', () => {
    const options = {
      url: '/api/single',
      initialQuery: mockSingleQuery,
    };

    const { result } = renderHook(() => useFetcherSingleQuery<any>(options));

    expect(result.current).toHaveProperty('execute');
    expect(result.current).toHaveProperty('setQuery');
    expect(result.current).toHaveProperty('getQuery');
    expect(result.current).toHaveProperty('loading');
    expect(result.current).toHaveProperty('result');
    expect(result.current).toHaveProperty('error');
    expect(result.current).toHaveProperty('status');
    expect(result.current).toHaveProperty('reset');
    expect(result.current).toHaveProperty('abort');
  });

  it('should handle different single query configurations', () => {
    const complexSingleQuery: SingleQuery<'name' | 'age' | 'status'> = {
      condition: contains('name', 'John'),
      sort: [{ field: 'age', direction: SortDirection.ASC }],
    };

    const options = {
      url: '/api/single-complex',
      initialQuery: complexSingleQuery,
    };

    renderHook(() =>
      useFetcherSingleQuery<any, 'name' | 'age' | 'status'>(options),
    );

    expect(useFetcherQuery).toHaveBeenCalledWith(options);
  });
});
