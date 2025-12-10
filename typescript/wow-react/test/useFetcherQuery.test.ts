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

import { renderHook, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { useFetcherQuery } from '../../src/wow/useFetcherQuery';
import { FetcherError } from '@ahoo-wang/fetcher';
import { PromiseStatus } from '../../src/core/usePromiseState';

// Mock the hooks
const mockExecute = vi.fn().mockResolvedValue(undefined);
const mockReset = vi.fn();
const mockAbort = vi.fn();

vi.mock('../../src/fetcher', () => ({
  useFetcher: vi.fn(() => ({
    loading: false,
    result: null,
    error: null,
    status: PromiseStatus.IDLE,
    execute: mockExecute,
    reset: mockReset,
    abort: mockAbort,
  })),
}));
vi.mock('../../src/core', () => ({
  useLatest: vi.fn(value => ({ current: value })),
}));

// Import after mocking
import { useFetcher } from '../../src/fetcher';
import { useLatest } from '../../src/core';

const mockUseFetcher = vi.mocked(useFetcher);
const mockUseLatest = vi.mocked(useLatest);

describe('useFetcherQuery', () => {
  beforeEach(() => {
    mockExecute.mockReset().mockResolvedValue(undefined);
    mockReset.mockReset();
    mockAbort.mockReset();
    mockUseFetcher.mockClear();
    mockUseLatest.mockClear();
  });

  afterEach(() => {
    vi.clearAllMocks();
  });
  afterEach(() => {
    vi.clearAllMocks();
  });

  describe('basic functionality', () => {
    it('should initialize with correct state', () => {
      const options = {
        url: '/api/test',
        initialQuery: { id: 'test' },
      };

      const { result } = renderHook(() => useFetcherQuery(options));

      expect(result.current.loading).toBe(false);
      expect(result.current.result).toBeNull();
      expect(result.current.error).toBeNull();
      expect(result.current.status).toBe('idle');
      expect(typeof result.current.execute).toBe('function');
      expect(typeof result.current.getQuery).toBe('function');
      expect(typeof result.current.setQuery).toBe('function');
      expect(typeof result.current.reset).toBe('function');
      expect(typeof result.current.abort).toBe('function');
    });

    it('should return initial query via getQuery', () => {
      const initialQuery = { id: 'test', filters: { active: true } };
      const options = {
        url: '/api/test',
        initialQuery,
      };

      const { result } = renderHook(() => useFetcherQuery(options));

      expect(result.current.getQuery()).toEqual(initialQuery);
    });

    it('should update query via setQuery', () => {
      const initialQuery = { id: 'initial' };
      const newQuery = { id: 'updated' };
      const options = {
        url: '/api/test',
        initialQuery,
      };

      const { result } = renderHook(() => useFetcherQuery(options));

      act(() => {
        result.current.setQuery(newQuery);
      });

      expect(result.current.getQuery()).toEqual(newQuery);
    });

    it('should handle different query types', () => {
      // String query
      const stringQuery = 'test query';
      const { result: stringResult } = renderHook(() =>
        useFetcherQuery({
          url: '/api/test',
          initialQuery: stringQuery,
        }),
      );
      expect(stringResult.current.getQuery()).toBe(stringQuery);

      // Array query
      const arrayQuery = [1, 2, 3];
      const { result: arrayResult } = renderHook(() =>
        useFetcherQuery({
          url: '/api/test',
          initialQuery: arrayQuery,
        }),
      );
      expect(arrayResult.current.getQuery()).toEqual(arrayQuery);

      // Complex object query
      const complexQuery = {
        user: { id: 1, name: 'John' },
        filters: { active: true, tags: ['a', 'b'] },
        pagination: { page: 1, limit: 10 },
      };
      const { result: complexResult } = renderHook(() =>
        useFetcherQuery({
          url: '/api/test',
          initialQuery: complexQuery,
        }),
      );
      expect(complexResult.current.getQuery()).toEqual(complexQuery);
    });
  });

  describe('fetcher integration', () => {
    it('should call useFetcher with correct options', () => {
      const options = {
        url: '/api/test',
        initialQuery: { id: 'test' },
        timeout: 5000,
        headers: { 'X-Custom': 'value' },
      };

      renderHook(() => useFetcherQuery(options));

      expect(mockUseLatest).toHaveBeenCalledWith(options);
      expect(mockUseFetcher).toHaveBeenCalledWith(options);
    });

    it('should construct correct FetchRequest for execute', async () => {
      const query = { id: 'test', data: { nested: 'value' } };
      const url = '/api/search';
      const options = {
        url,
        initialQuery: { id: 'initial' },
      };

      const { result } = renderHook(() => useFetcherQuery(options));

      act(() => {
        result.current.setQuery(query);
      });

      act(() => {
        result.current.execute();
      });

      expect(mockExecute).toHaveBeenCalledWith({
        url,
        method: 'POST',
        body: query,
      });
    });

    it('should handle execute with current query', async () => {
      const initialQuery = { id: 'initial' };
      const options = {
        url: '/api/test',
        initialQuery,
      };

      const { result } = renderHook(() => useFetcherQuery(options));

      act(() => {
        result.current.execute();
      });

      expect(mockExecute).toHaveBeenCalledWith({
        url: '/api/test',
        method: 'POST',
        body: initialQuery,
      });
    });

    it('should pass through fetcher state correctly', () => {
      mockUseFetcher.mockReturnValueOnce({
        loading: true,
        result: null,
        error: null,
        status: PromiseStatus.LOADING,
        execute: mockExecute,
        reset: mockReset,
        abort: mockAbort,
      });

      const { result } = renderHook(() =>
        useFetcherQuery({
          url: '/api/test',
          initialQuery: { id: 'test' },
        }),
      );

      expect(result.current.loading).toBe(true);
      expect(result.current.status).toBe(PromiseStatus.LOADING);
    });

    it('should pass through fetcher result and error', () => {
      mockUseFetcher.mockReturnValueOnce({
        loading: false,
        result: { data: 'success' },
        error: null,
        status: PromiseStatus.SUCCESS,
        execute: mockExecute,
        reset: mockReset,
        abort: mockAbort,
      });

      const { result: successResult } = renderHook(() =>
        useFetcherQuery({
          url: '/api/test',
          initialQuery: { id: 'test' },
        }),
      );

      expect(successResult.current.result).toEqual({ data: 'success' });
      expect(successResult.current.status).toBe('success');

      mockUseFetcher.mockReturnValueOnce({
        loading: false,
        result: null,
        error: new FetcherError('Network error'),
        status: PromiseStatus.ERROR,
        execute: mockExecute,
        reset: mockReset,
        abort: mockAbort,
      });

      const { result: errorResult } = renderHook(() =>
        useFetcherQuery({
          url: '/api/test',
          initialQuery: { id: 'test' },
        }),
      );

      expect(errorResult.current.error).toBeInstanceOf(FetcherError);
      expect(errorResult.current.error?.message).toBe('Network error');
      expect(errorResult.current.status).toBe('error');
    });
  });

  describe('autoExecute behavior', () => {
    it('should execute on mount when autoExecute is true', async () => {
      const initialQuery = { id: 'test' };
      const options = {
        url: '/api/test',
        initialQuery,
        autoExecute: true,
      };

      renderHook(() => useFetcherQuery(options));

      await vi.waitFor(() => {
        expect(mockExecute).toHaveBeenCalledWith({
          url: '/api/test',
          method: 'POST',
          body: initialQuery,
        });
      });
    });

    it('should not execute on mount when autoExecute is false', () => {
      const options = {
        url: '/api/test',
        initialQuery: { id: 'test' },
        autoExecute: false,
      };

      renderHook(() => useFetcherQuery(options));

      expect(mockExecute).not.toHaveBeenCalled();
    });

    it('should not execute on mount when autoExecute is undefined', () => {
      const options = {
        url: '/api/test',
        initialQuery: { id: 'test' },
      };

      renderHook(() => useFetcherQuery(options));

      expect(mockExecute).not.toHaveBeenCalled();
    });

    it('should execute on setQuery when autoExecute is true', async () => {
      const initialQuery = { id: 'initial' };
      const newQuery = { id: 'updated' };
      const options = {
        url: '/api/test',
        initialQuery,
        autoExecute: true,
      };

      const { result } = renderHook(() => useFetcherQuery(options));

      // Wait for initial execution
      await vi.waitFor(() => {
        expect(mockExecute).toHaveBeenCalledTimes(1);
      });

      mockExecute.mockClear();

      act(() => {
        result.current.setQuery(newQuery);
      });

      await vi.waitFor(() => {
        expect(mockExecute).toHaveBeenCalledWith({
          url: '/api/test',
          method: 'POST',
          body: newQuery,
        });
      });
    });

    it('should not execute on setQuery when autoExecute is false', () => {
      const initialQuery = { id: 'initial' };
      const newQuery = { id: 'updated' };
      const options = {
        url: '/api/test',
        initialQuery,
        autoExecute: false,
      };

      const { result } = renderHook(() => useFetcherQuery(options));

      act(() => {
        result.current.setQuery(newQuery);
      });

      expect(mockExecute).not.toHaveBeenCalled();
    });
  });

  describe('error handling and edge cases', () => {
    it('should handle fetcher execute rejection', async () => {
      const initialQuery = { id: 'test' };
      const error = new FetcherError('Request failed');
      mockExecute.mockRejectedValueOnce(error);

      renderHook(() =>
        useFetcherQuery({
          url: '/api/test',
          initialQuery,
          autoExecute: true,
        }),
      );

      // The hook should still call execute even if it rejects
      await vi.waitFor(() => {
        expect(mockExecute).toHaveBeenCalledWith({
          url: '/api/test',
          method: 'POST',
          body: initialQuery,
        });
      });

      // Verify the promise rejects as expected
      await expect(mockExecute.mock.results[0].value).rejects.toThrow(
        'Request failed',
      );
    });

    it('should handle malformed URLs gracefully', () => {
      const options = {
        url: 'invalid-url',
        initialQuery: { id: 'test' },
      };

      expect(() => {
        renderHook(() => useFetcherQuery(options));
      }).not.toThrow();
    });

    it('should handle empty and null query bodies', async () => {
      const emptyQuery = {};
      const { result: emptyResult } = renderHook(() =>
        useFetcherQuery({
          url: '/api/test',
          initialQuery: emptyQuery,
        }),
      );

      act(() => {
        emptyResult.current.execute();
      });

      expect(mockExecute).toHaveBeenCalledWith({
        url: '/api/test',
        method: 'POST',
        body: emptyQuery,
      });

      // Test with null (though initialQuery shouldn't be null in practice)
      const nullQuery = null as any;
      const { result: nullResult } = renderHook(() =>
        useFetcherQuery({
          url: '/api/test',
          initialQuery: nullQuery,
        }),
      );

      act(() => {
        nullResult.current.execute();
      });

      expect(mockExecute).toHaveBeenCalledWith({
        url: '/api/test',
        method: 'POST',
        body: nullQuery,
      });
    });

    it('should handle large query objects', () => {
      const largeQuery = {
        data: Array.from({ length: 1000 }, (_, i) => ({
          id: i,
          value: `item${i}`,
        })),
        metadata: { total: 1000, page: 1 },
      };

      const { result } = renderHook(() =>
        useFetcherQuery({
          url: '/api/test',
          initialQuery: largeQuery,
        }),
      );

      expect(result.current.getQuery()).toEqual(largeQuery);

      act(() => {
        result.current.execute();
      });

      expect(mockExecute).toHaveBeenCalledWith({
        url: '/api/test',
        method: 'POST',
        body: largeQuery,
      });
    });

    it('should handle special characters in query data', () => {
      const specialQuery = {
        text: 'Hello & World <script>alert("xss")</script>',
        symbols: '!@#$%^&*()',
        unicode: '🚀 🌟 💻',
      };

      const { result } = renderHook(() =>
        useFetcherQuery({
          url: '/api/test',
          initialQuery: specialQuery,
        }),
      );

      act(() => {
        result.current.execute();
      });

      expect(mockExecute).toHaveBeenCalledWith({
        url: '/api/test',
        method: 'POST',
        body: specialQuery,
      });
    });
  });
});
