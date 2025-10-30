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

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useDebouncedQuery } from '../../../src';

// Mock the dependencies
vi.mock('../../../src/wow/useQuery', () => ({
  useQuery: vi.fn(),
}));

vi.mock('../../../src/core', () => ({
  useDebouncedCallback: vi.fn(),
}));

import { useQuery } from '../../../src';
import { useDebouncedCallback } from '../../../src';

describe('useDebouncedQuery', () => {
  const mockResult = { id: 1, name: 'Test Item' };
  const initialQuery = { id: '1', filter: 'active' };

  const mockExecute = vi.fn().mockResolvedValue(mockResult);
  const mockReset = vi.fn();
  const mockGetQuery = vi.fn().mockReturnValue(initialQuery);
  const mockSetQuery = vi.fn();

  const mockQueryReturn = {
    loading: false,
    result: null,
    error: null,
    status: 'idle',
    execute: mockExecute,
    reset: mockReset,
    getQuery: mockGetQuery,
    setQuery: mockSetQuery,
  };

  const mockDebouncedReturn = {
    run: vi.fn(),
    cancel: vi.fn(),
    isPending: vi.fn().mockReturnValue(false),
  };

  beforeEach(() => {
    vi.clearAllMocks();
    (useQuery as any).mockReturnValue(mockQueryReturn);
    (useDebouncedCallback as any).mockReturnValue(mockDebouncedReturn);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('should return correct interface combining query and debounced functionality', () => {
    const options = {
      initialQuery,
      execute: mockExecute,
      debounce: { delay: 300 },
    };

    const { result } = renderHook(() => useDebouncedQuery(options));

    expect(result.current).toHaveProperty('loading', false);
    expect(result.current).toHaveProperty('result', null);
    expect(result.current).toHaveProperty('error', null);
    expect(result.current).toHaveProperty('status', 'idle');
    expect(result.current).toHaveProperty('reset', mockReset);
    expect(result.current).toHaveProperty('getQuery', mockGetQuery);
    expect(result.current).toHaveProperty('setQuery', mockSetQuery);
    expect(result.current).toHaveProperty('run', mockDebouncedReturn.run);
    expect(result.current).toHaveProperty('cancel', mockDebouncedReturn.cancel);
    expect(result.current).toHaveProperty(
      'isPending',
      mockDebouncedReturn.isPending,
    );
    expect(result.current).not.toHaveProperty('execute'); // execute should be omitted
  });

  it('should pass options to useQuery and debounce config to useDebouncedCallback', () => {
    const options = {
      initialQuery,
      execute: mockExecute,
      debounce: { delay: 300, leading: true },
    };

    renderHook(() => useDebouncedQuery(options));

    expect(useQuery).toHaveBeenCalledWith(options);
    expect(useDebouncedCallback).toHaveBeenCalledWith(
      mockExecute,
      options.debounce,
    );
  });

  it('should expose debounced run method for executing queries', () => {
    const options = {
      initialQuery,
      execute: mockExecute,
      debounce: { delay: 300 },
    };

    const { result } = renderHook(() => useDebouncedQuery(options));

    act(() => {
      result.current.run();
    });

    expect(mockDebouncedReturn.run).toHaveBeenCalled();
  });

  it('should expose cancel method to cancel pending debounced queries', () => {
    const options = {
      initialQuery,
      execute: mockExecute,
      debounce: { delay: 300 },
    };

    const { result } = renderHook(() => useDebouncedQuery(options));

    act(() => {
      result.current.cancel();
    });

    expect(mockDebouncedReturn.cancel).toHaveBeenCalled();
  });

  it('should expose isPending to check if debounced query is pending', () => {
    mockDebouncedReturn.isPending.mockReturnValue(true);

    const options = {
      initialQuery,
      execute: mockExecute,
      debounce: { delay: 300 },
    };

    const { result } = renderHook(() => useDebouncedQuery(options));

    expect(result.current.isPending()).toBe(true);
    expect(mockDebouncedReturn.isPending).toHaveBeenCalled();
  });
});
