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
import { useListStreamQuery } from '../../src';
import { ListQuery, all, SortDirection } from '@ahoo-wang/fetcher-wow';
import type { JsonServerSentEvent } from '@ahoo-wang/fetcher-eventstream';

// Mock useQuery
vi.mock('../../src/core/useQuery', () => ({
  useQuery: vi.fn(),
}));

import { useQuery } from '../../src';

describe('useListStreamQuery', () => {
  const mockStream = new ReadableStream<
    JsonServerSentEvent<{ id: number; name: string }>
  >();
  const mockUseQueryReturn = {
    data: mockStream,
    loading: false,
    error: null,
    execute: vi.fn(),
    reset: vi.fn(),
  };

  const initialQuery: ListQuery<'id' | 'name'> = {
    condition: all(),
    projection: { include: ['id', 'name'] },
    sort: [{ field: 'id', direction: SortDirection.ASC }],
  };

  beforeEach(() => {
    vi.clearAllMocks();
    (useQuery as any).mockReturnValue(mockUseQueryReturn);
  });

  it('should call useQuery with the provided options', () => {
    const execute = vi.fn().mockResolvedValue(mockStream);
    const options = {
      initialQuery,
      execute,
    };

    renderHook(() =>
      useListStreamQuery<{ id: number; name: string }, 'id' | 'name'>(options),
    );

    expect(useQuery).toHaveBeenCalledWith(options);
  });

  it('should return the result from useQuery', () => {
    const execute = vi.fn().mockResolvedValue(mockStream);
    const options = {
      initialQuery,
      execute,
    };

    const { result } = renderHook(() =>
      useListStreamQuery<{ id: number; name: string }, 'id' | 'name'>(options),
    );

    expect(result.current).toBe(mockUseQueryReturn);
  });

  it('should work with custom result and field types', () => {
    const customStream = new ReadableStream<
      JsonServerSentEvent<{ customId: number; customName: string }>
    >();
    const customInitialQuery: ListQuery<'customId' | 'customName'> = {
      condition: all(),
      projection: { include: ['customId', 'customName'] },
      sort: [{ field: 'customId', direction: SortDirection.DESC }],
    };
    const execute = vi.fn().mockResolvedValue(customStream);
    const options = {
      initialQuery: customInitialQuery,
      execute,
    };

    renderHook(() =>
      useListStreamQuery<
        { customId: number; customName: string },
        'customId' | 'customName'
      >(options),
    );

    expect(useQuery).toHaveBeenCalledWith(options);
  });

  it('should work with custom error types', () => {
    const customError = new Error('Custom error');
    const mockReturnWithError = { ...mockUseQueryReturn, error: customError };
    (useQuery as any).mockReturnValue(mockReturnWithError);

    const execute = vi.fn().mockRejectedValue(customError);
    const options = {
      initialQuery,
      execute,
    };

    const { result } = renderHook(() =>
      useListStreamQuery<{ id: number; name: string }, 'id' | 'name', Error>(
        options,
      ),
    );

    expect(result.current.error).toBe(customError);
  });
});
