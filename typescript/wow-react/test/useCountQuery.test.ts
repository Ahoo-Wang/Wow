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
import { useCountQuery } from '../../src';
import { Condition, all } from '@ahoo-wang/fetcher-wow';

// Mock useQuery
vi.mock('../../src/wow/useQuery', () => ({
  useQuery: vi.fn(),
}));

import { useQuery } from '../../src';

describe('useCountQuery', () => {
  const mockUseQueryReturn = {
    data: 42,
    loading: false,
    error: null,
    execute: vi.fn(),
    reset: vi.fn(),
  };

  beforeEach(() => {
    vi.clearAllMocks();
    (useQuery as any).mockReturnValue(mockUseQueryReturn);
  });

  it('should call useQuery with the provided options', () => {
    const initialQuery = all() as Condition<string>;
    const execute = vi.fn().mockResolvedValue(42);
    const options = {
      initialQuery,
      execute,
    };

    renderHook(() => useCountQuery(options));

    expect(useQuery).toHaveBeenCalledWith(options);
  });

  it('should return the result from useQuery', () => {
    const initialQuery = all() as Condition<string>;
    const execute = vi.fn().mockResolvedValue(0);
    const options = {
      initialQuery,
      execute,
    };

    const { result } = renderHook(() => useCountQuery(options));

    expect(result.current).toBe(mockUseQueryReturn);
  });

  it('should work with custom field types', () => {
    const initialQuery = all() as Condition<'id' | 'name'>;
    const execute = vi.fn().mockResolvedValue(5);
    const options = {
      initialQuery,
      execute,
    };

    renderHook(() => useCountQuery<'id' | 'name'>(options));

    expect(useQuery).toHaveBeenCalledWith(options);
  });

  it('should work with custom error types', () => {
    const customError = new Error('Custom error');
    const mockReturnWithError = { ...mockUseQueryReturn, error: customError };
    (useQuery as any).mockReturnValue(mockReturnWithError);

    const initialQuery = all() as Condition<string>;
    const execute = vi.fn().mockRejectedValue(customError);
    const options = {
      initialQuery,
      execute,
    };

    const { result } = renderHook(() => useCountQuery<string, Error>(options));

    expect(result.current.error).toBe(customError);
  });
});
