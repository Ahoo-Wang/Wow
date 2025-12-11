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
import { useDebouncedFetcherQuery } from '../../../src/wow/debounce/useDebouncedFetcherQuery';
import { FetcherError } from '@ahoo-wang/fetcher';
import { PromiseStatus } from '../../../src/core/usePromiseState';

// Mock the dependencies
vi.mock('../../../src/wow/useFetcherQuery', () => ({
  useFetcherQuery: vi.fn(),
}));

vi.mock('../../../src/core', () => ({
  useDebouncedCallback: vi.fn(),
}));

import { useFetcherQuery } from '../../../src/wow/useFetcherQuery';
import { useDebouncedCallback } from '../../../src/core';

const mockUseFetcherQuery = vi.mocked(useFetcherQuery);
const mockUseDebouncedCallback = vi.mocked(useDebouncedCallback);

describe('useDebouncedFetcherQuery', () => {
  const mockResult = { data: 'success', items: [1, 2, 3] };
  const initialQuery = { search: 'test', limit: 10, filters: { active: true } };

  const mockExecute = vi.fn().mockResolvedValue(undefined);
  const mockReset = vi.fn();
  const mockAbort = vi.fn();
  const mockGetQuery = vi.fn().mockReturnValue(initialQuery);
  const mockSetQuery = vi.fn();

  const mockFetcherQueryReturn = {
    loading: false,
    result: null,
    error: null,
    status: PromiseStatus.IDLE,
    execute: mockExecute,
    reset: mockReset,
    abort: mockAbort,
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
    mockUseFetcherQuery.mockReturnValue(mockFetcherQueryReturn);
    mockUseDebouncedCallback.mockReturnValue(mockDebouncedReturn);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  describe('basic functionality', () => {
    it('should return correct interface combining fetcher query and debounced functionality', () => {
      const options = {
        url: '/api/search',
        initialQuery,
        debounce: { delay: 300 },
      };

      const { result } = renderHook(() => useDebouncedFetcherQuery(options));

      expect(result.current).toHaveProperty('loading', false);
      expect(result.current).toHaveProperty('result', null);
      expect(result.current).toHaveProperty('error', null);
      expect(result.current).toHaveProperty('status', PromiseStatus.IDLE);
      expect(result.current).toHaveProperty('reset', mockReset);
      expect(result.current).toHaveProperty('abort', mockAbort);
      expect(result.current).toHaveProperty('getQuery', mockGetQuery);
      expect(result.current).toHaveProperty('setQuery', mockSetQuery);
      expect(result.current).toHaveProperty('run', mockDebouncedReturn.run);
      expect(result.current).toHaveProperty(
        'cancel',
        mockDebouncedReturn.cancel,
      );
      expect(result.current).toHaveProperty(
        'isPending',
        mockDebouncedReturn.isPending,
      );
      expect(result.current).not.toHaveProperty('execute'); // execute should be omitted
    });

    it('should pass options to useFetcherQuery and debounce config to useDebouncedCallback', () => {
      const options = {
        url: '/api/search',
        initialQuery,
        debounce: { delay: 500, leading: true },
        autoExecute: false,
      };

      renderHook(() => useDebouncedFetcherQuery(options));

      expect(mockUseFetcherQuery).toHaveBeenCalledWith(options);
      expect(mockUseDebouncedCallback).toHaveBeenCalledWith(
        mockExecute,
        options.debounce,
      );
    });

    it('should memoize the return object correctly', () => {
      const options = {
        url: '/api/search',
        initialQuery,
        debounce: { delay: 300 },
      };

      const { result, rerender } = renderHook(() =>
        useDebouncedFetcherQuery(options),
      );

      const firstReturn = result.current;

      rerender();

      expect(result.current).toBe(firstReturn); // Same reference due to memoization
    });
  });

  describe('debouncing behavior', () => {
    beforeEach(() => {
      vi.useFakeTimers();
    });

    afterEach(() => {
      vi.useRealTimers();
    });

    it('should expose debounced run method for executing queries', () => {
      const options = {
        url: '/api/search',
        initialQuery,
        debounce: { delay: 300 },
      };

      const { result } = renderHook(() => useDebouncedFetcherQuery(options));

      act(() => {
        result.current.run();
      });

      expect(mockDebouncedReturn.run).toHaveBeenCalled();
    });

    it('should expose cancel method to cancel pending debounced queries', () => {
      const options = {
        url: '/api/search',
        initialQuery,
        debounce: { delay: 300 },
      };

      const { result } = renderHook(() => useDebouncedFetcherQuery(options));

      act(() => {
        result.current.cancel();
      });

      expect(mockDebouncedReturn.cancel).toHaveBeenCalled();
    });

    it('should expose isPending to check if debounced query is pending', () => {
      mockDebouncedReturn.isPending.mockReturnValue(true);

      const options = {
        url: '/api/search',
        initialQuery,
        debounce: { delay: 300 },
      };

      const { result } = renderHook(() => useDebouncedFetcherQuery(options));

      expect(result.current.isPending()).toBe(true);
      expect(mockDebouncedReturn.isPending).toHaveBeenCalled();
    });

    it('should handle rapid successive calls correctly', () => {
      const options = {
        url: '/api/search',
        initialQuery,
        debounce: { delay: 300 },
      };

      const { result } = renderHook(() => useDebouncedFetcherQuery(options));

      act(() => {
        result.current.run();
        result.current.run();
        result.current.run();
      });

      expect(mockDebouncedReturn.run).toHaveBeenCalledTimes(3);
    });

    it('should work with different debounce configurations', () => {
      const testCases = [
        { delay: 100 },
        { delay: 500, leading: true },
        { delay: 200, trailing: false },
        { delay: 0 },
      ];

      testCases.forEach(debounceConfig => {
        const options = {
          url: '/api/search',
          initialQuery,
          debounce: debounceConfig,
        };

        renderHook(() => useDebouncedFetcherQuery(options));

        expect(mockUseDebouncedCallback).toHaveBeenCalledWith(
          mockExecute,
          debounceConfig,
        );
      });
    });
  });

  describe('state propagation', () => {
    it('should propagate loading state from fetcher query', () => {
      mockUseFetcherQuery.mockReturnValueOnce({
        ...mockFetcherQueryReturn,
        loading: true,
        status: PromiseStatus.LOADING,
      });

      const options = {
        url: '/api/search',
        initialQuery,
        debounce: { delay: 300 },
      };

      const { result } = renderHook(() => useDebouncedFetcherQuery(options));

      expect(result.current.loading).toBe(true);
      expect(result.current.status).toBe(PromiseStatus.LOADING);
    });

    it('should propagate result and error states from fetcher query', () => {
      // Test success state
      mockUseFetcherQuery.mockReturnValueOnce({
        ...mockFetcherQueryReturn,
        loading: false,
        result: mockResult,
        error: null,
        status: PromiseStatus.SUCCESS,
      });

      const successOptions = {
        url: '/api/search',
        initialQuery,
        debounce: { delay: 300 },
      };

      const { result: successResult } = renderHook(() =>
        useDebouncedFetcherQuery(successOptions),
      );

      expect(successResult.current.result).toEqual(mockResult);
      expect(successResult.current.error).toBeNull();
      expect(successResult.current.status).toBe(PromiseStatus.SUCCESS);

      // Test error state
      const error = new FetcherError('Network error');
      mockUseFetcherQuery.mockReturnValueOnce({
        ...mockFetcherQueryReturn,
        loading: false,
        result: null,
        error,
        status: PromiseStatus.ERROR,
      });

      const errorOptions = {
        url: '/api/search',
        initialQuery,
        debounce: { delay: 300 },
      };

      const { result: errorResult } = renderHook(() =>
        useDebouncedFetcherQuery(errorOptions),
      );

      expect(errorResult.current.result).toBeNull();
      expect(errorResult.current.error).toBe(error);
      expect(errorResult.current.status).toBe(PromiseStatus.ERROR);
    });

    it('should propagate query management functions', () => {
      const options = {
        url: '/api/search',
        initialQuery,
        debounce: { delay: 300 },
      };

      const { result } = renderHook(() => useDebouncedFetcherQuery(options));

      expect(result.current.getQuery).toBe(mockGetQuery);
      expect(result.current.setQuery).toBe(mockSetQuery);
    });

    it('should propagate control functions', () => {
      const options = {
        url: '/api/search',
        initialQuery,
        debounce: { delay: 300 },
      };

      const { result } = renderHook(() => useDebouncedFetcherQuery(options));

      expect(result.current.reset).toBe(mockReset);
      expect(result.current.abort).toBe(mockAbort);
    });
  });

  describe('options handling', () => {
    it('should handle different query types', () => {
      const testQueries = [
        { search: 'string query' },
        [1, 2, 3],
        { complex: { nested: { data: 'value' } } },
        null,
        undefined,
      ];

      testQueries.forEach(query => {
        mockGetQuery.mockReturnValue(query);

        const options = {
          url: '/api/search',
          initialQuery: query,
          debounce: { delay: 300 },
        };

        const { result } = renderHook(() => useDebouncedFetcherQuery(options));

        expect(result.current.getQuery()).toBe(query);
      });
    });

    it('should handle various debounce configurations', () => {
      const debounceConfigs = [
        { delay: 100 },
        { delay: 1000, leading: true },
        { delay: 500, trailing: false },
        { delay: 200, leading: true, trailing: true },
        { delay: 0 },
      ];

      debounceConfigs.forEach(debounce => {
        const options = {
          url: '/api/search',
          initialQuery,
          debounce,
        };

        renderHook(() => useDebouncedFetcherQuery(options));

        expect(mockUseDebouncedCallback).toHaveBeenCalledWith(
          mockExecute,
          debounce,
        );
      });
    });

    it('should pass through all fetcher query options', () => {
      const options = {
        url: '/api/search',
        initialQuery,
        debounce: { delay: 300 },
        autoExecute: true,
        resultExtractor: vi.fn(),
        onSuccess: vi.fn(),
        onError: vi.fn(),
      };

      renderHook(() => useDebouncedFetcherQuery(options));

      expect(mockUseFetcherQuery).toHaveBeenCalledWith(options);
    });
  });

  describe('edge cases and error handling', () => {
    it('should handle undefined debounce options gracefully', () => {
      const options = {
        url: '/api/search',
        initialQuery,
        debounce: undefined as any,
      };

      expect(() => {
        renderHook(() => useDebouncedFetcherQuery(options));
      }).not.toThrow();
    });

    it('should handle empty options object', () => {
      const options = {} as any;

      // Since we mock the dependencies, it won't throw validation errors
      // This test verifies the hook doesn't crash with minimal options
      expect(() => {
        renderHook(() => useDebouncedFetcherQuery(options));
      }).not.toThrow();
    });

    it('should handle rapid re-renders without breaking memoization', () => {
      const options = {
        url: '/api/search',
        initialQuery,
        debounce: { delay: 300 },
      };

      const { result, rerender } = renderHook(() =>
        useDebouncedFetcherQuery(options),
      );

      const initialReturn = result.current;

      // Simulate rapid re-renders
      for (let i = 0; i < 10; i++) {
        rerender();
        expect(result.current).toBe(initialReturn);
      }
    });

    it('should handle component unmounting gracefully', () => {
      const options = {
        url: '/api/search',
        initialQuery,
        debounce: { delay: 300 },
      };

      const { unmount } = renderHook(() => useDebouncedFetcherQuery(options));

      expect(() => {
        unmount();
      }).not.toThrow();
    });

    it('should handle fetcher query returning different function references', () => {
      const newExecute = vi.fn();
      const newReset = vi.fn();
      const newAbort = vi.fn();

      const options = {
        url: '/api/search',
        initialQuery,
        debounce: { delay: 300 },
      };

      const { result, rerender } = renderHook(() =>
        useDebouncedFetcherQuery(options),
      );

      // Change the mock return value
      mockUseFetcherQuery.mockReturnValue({
        ...mockFetcherQueryReturn,
        execute: newExecute,
        reset: newReset,
        abort: newAbort,
      });

      rerender();

      // The return object should be different due to memoization dependencies
      expect(result.current.reset).toBe(newReset);
      expect(result.current.abort).toBe(newAbort);
    });

    it('should handle debounced callback returning different function references', () => {
      const newRun = vi.fn();
      const newCancel = vi.fn();
      const newIsPending = vi.fn();

      const options = {
        url: '/api/search',
        initialQuery,
        debounce: { delay: 300 },
      };

      const { result, rerender } = renderHook(() =>
        useDebouncedFetcherQuery(options),
      );

      // Change the mock return value
      mockUseDebouncedCallback.mockReturnValue({
        run: newRun,
        cancel: newCancel,
        isPending: newIsPending,
      });

      rerender();

      // The return object should be different due to memoization dependencies
      expect(result.current.run).toBe(newRun);
      expect(result.current.cancel).toBe(newCancel);
      expect(result.current.isPending).toBe(newIsPending);
    });
  });

  describe('integration scenarios', () => {
    it('should work with autoExecute enabled', () => {
      const options = {
        url: '/api/search',
        initialQuery,
        debounce: { delay: 300 },
        autoExecute: true,
      };

      renderHook(() => useDebouncedFetcherQuery(options));

      expect(mockUseFetcherQuery).toHaveBeenCalledWith(
        expect.objectContaining({
          autoExecute: true,
        }),
      );
    });

    it('should handle complex query objects', () => {
      const complexQuery = {
        search: {
          term: 'advanced search',
          filters: {
            dateRange: { start: '2023-01-01', end: '2023-12-31' },
            categories: ['tech', 'business'],
            tags: [
              { id: 1, name: 'urgent' },
              { id: 2, name: 'important' },
            ],
          },
          pagination: {
            page: 1,
            limit: 50,
            sortBy: 'relevance',
            sortOrder: 'desc',
          },
        },
        metadata: {
          requestId: 'req-123',
          userId: 'user-456',
          sessionId: 'sess-789',
        },
      };

      mockGetQuery.mockReturnValue(complexQuery);

      const options = {
        url: '/api/search',
        initialQuery: complexQuery,
        debounce: { delay: 300 },
      };

      const { result } = renderHook(() => useDebouncedFetcherQuery(options));

      expect(result.current.getQuery()).toEqual(complexQuery);
    });

    it('should handle array-based queries', () => {
      const arrayQuery = [
        { id: 1, action: 'create' },
        { id: 2, action: 'update' },
        { id: 3, action: 'delete' },
      ];

      mockGetQuery.mockReturnValue(arrayQuery);

      const options = {
        url: '/api/batch',
        initialQuery: arrayQuery,
        debounce: { delay: 300 },
      };

      const { result } = renderHook(() => useDebouncedFetcherQuery(options));

      expect(result.current.getQuery()).toEqual(arrayQuery);
    });

    it('should handle primitive queries', () => {
      const primitiveQueries = ['string query', 42, true, null, undefined];

      primitiveQueries.forEach(query => {
        mockGetQuery.mockReturnValue(query);

        const options = {
          url: '/api/search',
          initialQuery: query,
          debounce: { delay: 300 },
        };

        const { result } = renderHook(() => useDebouncedFetcherQuery(options));

        expect(result.current.getQuery()).toBe(query);
      });
    });
  });

  describe('performance and memory', () => {
    it('should not recreate functions unnecessarily', () => {
      const options = {
        url: '/api/search',
        initialQuery,
        debounce: { delay: 300 },
      };

      const { result, rerender } = renderHook(() =>
        useDebouncedFetcherQuery(options),
      );

      const initialRun = result.current.run;
      const initialCancel = result.current.cancel;
      const initialIsPending = result.current.isPending;

      rerender();

      expect(result.current.run).toBe(initialRun);
      expect(result.current.cancel).toBe(initialCancel);
      expect(result.current.isPending).toBe(initialIsPending);
    });

    it('should update when dependencies change', () => {
      mockUseDebouncedCallback.mockClear();

      const { rerender } = renderHook(
        debounce =>
          useDebouncedFetcherQuery({
            url: '/api/search',
            initialQuery,
            debounce,
          }),
        { initialProps: { delay: 300 } },
      );

      expect(mockUseDebouncedCallback).toHaveBeenCalledWith(mockExecute, {
        delay: 300,
      });

      mockUseDebouncedCallback.mockClear();

      // Change debounce config
      rerender({ delay: 500 });

      // useDebouncedCallback should be called again with new config
      expect(mockUseDebouncedCallback).toHaveBeenCalledWith(mockExecute, {
        delay: 500,
      });
    });
  });
});
