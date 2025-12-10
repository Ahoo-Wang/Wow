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
import { describe, it, expect, vi } from 'vitest';
import { useQueryState } from '../../src';

describe('useQueryState', () => {
  describe('basic functionality', () => {
    it('should return initial query via getQuery', () => {
      const initialQuery = { id: 'test', limit: 10 };
      const execute = vi.fn().mockResolvedValue(undefined);

      const { result } = renderHook(() =>
        useQueryState({
          initialQuery,
          execute,
        }),
      );

      expect(result.current.getQuery()).toEqual(initialQuery);
      expect(execute).not.toHaveBeenCalled();
    });

    it('should update query via setQuery', () => {
      const initialQuery = { id: 'initial' };
      const newQuery = { id: 'updated' };
      const execute = vi.fn().mockResolvedValue(undefined);

      const { result } = renderHook(() =>
        useQueryState({
          initialQuery,
          execute,
        }),
      );

      act(() => {
        result.current.setQuery(newQuery);
      });

      expect(result.current.getQuery()).toEqual(newQuery);
      expect(execute).not.toHaveBeenCalled();
    });

    it('should handle primitive query types', () => {
      const initialQuery = 'initial string';
      const newQuery = 'updated string';
      const execute = vi.fn().mockResolvedValue(undefined);

      const { result } = renderHook(() =>
        useQueryState({
          initialQuery,
          execute,
        }),
      );

      expect(result.current.getQuery()).toBe(initialQuery);

      act(() => {
        result.current.setQuery(newQuery);
      });

      expect(result.current.getQuery()).toBe(newQuery);
    });

    it('should handle array query types', () => {
      const initialQuery = [1, 2, 3];
      const newQuery = [4, 5, 6];
      const execute = vi.fn().mockResolvedValue(undefined);

      const { result } = renderHook(() =>
        useQueryState({
          initialQuery,
          execute,
        }),
      );

      expect(result.current.getQuery()).toEqual(initialQuery);

      act(() => {
        result.current.setQuery(newQuery);
      });

      expect(result.current.getQuery()).toEqual(newQuery);
    });

    it('should handle null and undefined query values', () => {
      const execute = vi.fn().mockResolvedValue(undefined);

      // Test with null
      const { result: resultNull } = renderHook(() =>
        useQueryState({
          initialQuery: null as any,
          execute,
        }),
      );

      expect(resultNull.current.getQuery()).toBeNull();

      act(() => {
        resultNull.current.setQuery(undefined as any);
      });

      expect(resultNull.current.getQuery()).toBeUndefined();
    });
  });

  describe('autoExecute behavior', () => {
    it('should execute on mount when autoExecute is true', async () => {
      const initialQuery = { id: 'test' };
      const execute = vi.fn().mockResolvedValue(undefined);

      renderHook(() =>
        useQueryState({
          initialQuery,
          autoExecute: true,
          execute,
        }),
      );

      await vi.waitFor(() => {
        expect(execute).toHaveBeenCalledWith(initialQuery);
      });
    });

    it('should not execute on mount when autoExecute is false', () => {
      const initialQuery = { id: 'test' };
      const execute = vi.fn().mockResolvedValue(undefined);

      renderHook(() =>
        useQueryState({
          initialQuery,
          autoExecute: false,
          execute,
        }),
      );

      expect(execute).not.toHaveBeenCalled();
    });

    it('should not execute on mount when autoExecute is undefined', () => {
      const initialQuery = { id: 'test' };
      const execute = vi.fn().mockResolvedValue(undefined);

      renderHook(() =>
        useQueryState({
          initialQuery,
          execute,
        }),
      );

      expect(execute).not.toHaveBeenCalled();
    });

    it('should execute on setQuery when autoExecute is true', async () => {
      const initialQuery = { id: 'initial' };
      const newQuery = { id: 'updated' };
      const execute = vi.fn().mockResolvedValue(undefined);

      const { result } = renderHook(() =>
        useQueryState({
          initialQuery,
          autoExecute: true,
          execute,
        }),
      );

      // Wait for initial execution
      await vi.waitFor(() => {
        expect(execute).toHaveBeenCalledWith(initialQuery);
      });

      // Reset mock to check setQuery execution
      execute.mockClear();

      act(() => {
        result.current.setQuery(newQuery);
      });

      await vi.waitFor(() => {
        expect(execute).toHaveBeenCalledWith(newQuery);
      });
    });

    it('should not execute on setQuery when autoExecute is false', () => {
      const initialQuery = { id: 'initial' };
      const newQuery = { id: 'updated' };
      const execute = vi.fn().mockResolvedValue(undefined);

      const { result } = renderHook(() =>
        useQueryState({
          initialQuery,
          autoExecute: false,
          execute,
        }),
      );

      act(() => {
        result.current.setQuery(newQuery);
      });

      expect(execute).not.toHaveBeenCalled();
    });
  });

  describe('execute function behavior', () => {
    it('should pass correct query to execute function', async () => {
      const initialQuery = { id: 'test', filters: { active: true } };
      const execute = vi.fn().mockResolvedValue(undefined);

      const { result } = renderHook(() =>
        useQueryState({
          initialQuery,
          autoExecute: true,
          execute,
        }),
      );

      await vi.waitFor(() => {
        expect(execute).toHaveBeenCalledWith(initialQuery);
      });

      const newQuery = { id: 'updated', filters: { active: false } };
      execute.mockClear();

      act(() => {
        result.current.setQuery(newQuery);
      });

      await vi.waitFor(() => {
        expect(execute).toHaveBeenCalledWith(newQuery);
      });
    });

    it('should handle execute function that throws error', async () => {
      const initialQuery = { id: 'test' };
      const error = new Error('Execute failed');
      const execute = vi.fn().mockRejectedValue(error);

      renderHook(() =>
        useQueryState({
          initialQuery,
          autoExecute: true,
          execute,
        }),
      );

      // The hook should still call execute even if it rejects
      await vi.waitFor(() => {
        expect(execute).toHaveBeenCalledWith(initialQuery);
      });

      // Verify the promise rejects as expected
      await expect(execute.mock.results[0].value).rejects.toThrow(
        'Execute failed',
      );
    });

    it('should handle async execute function', async () => {
      const initialQuery = { id: 'test' };
      let executed = false;
      const execute = vi.fn().mockImplementation(async () => {
        await new Promise(resolve => setTimeout(resolve, 10));
        executed = true;
      });

      renderHook(() =>
        useQueryState({
          initialQuery,
          autoExecute: true,
          execute,
        }),
      );

      await vi.waitFor(() => {
        expect(executed).toBe(true);
        expect(execute).toHaveBeenCalledWith(initialQuery);
      });
    });
  });

  describe('multiple operations', () => {
    it('should handle multiple setQuery calls', async () => {
      const initialQuery = { id: 'initial' };
      const execute = vi.fn().mockResolvedValue(undefined);

      const { result } = renderHook(() =>
        useQueryState({
          initialQuery,
          autoExecute: true,
          execute,
        }),
      );

      // Wait for initial execution
      await vi.waitFor(() => {
        expect(execute).toHaveBeenCalledWith(initialQuery);
      });

      // Multiple setQuery calls
      const query1 = { id: 'first' };
      const query2 = { id: 'second' };
      const query3 = { id: 'third' };

      execute.mockClear();

      act(() => {
        result.current.setQuery(query1);
      });

      act(() => {
        result.current.setQuery(query2);
      });

      act(() => {
        result.current.setQuery(query3);
      });

      await vi.waitFor(() => {
        expect(execute).toHaveBeenCalledTimes(3);
        expect(execute).toHaveBeenNthCalledWith(1, query1);
        expect(execute).toHaveBeenNthCalledWith(2, query2);
        expect(execute).toHaveBeenNthCalledWith(3, query3);
      });

      expect(result.current.getQuery()).toEqual(query3);
    });

    it('should handle rapid setQuery calls without autoExecute', () => {
      const initialQuery = { id: 'initial' };
      const execute = vi.fn().mockResolvedValue(undefined);

      const { result } = renderHook(() =>
        useQueryState({
          initialQuery,
          autoExecute: false,
          execute,
        }),
      );

      const queries = [{ id: 'first' }, { id: 'second' }, { id: 'third' }];

      queries.forEach(query => {
        act(() => {
          result.current.setQuery(query);
        });
      });

      expect(execute).not.toHaveBeenCalled();
      expect(result.current.getQuery()).toEqual(queries[queries.length - 1]);
    });
  });

  describe('edge cases and error conditions', () => {
    it('should handle empty object queries', () => {
      const initialQuery = {};
      const newQuery = { key: 'value' };
      const execute = vi.fn().mockResolvedValue(undefined);

      const { result } = renderHook(() =>
        useQueryState({
          initialQuery,
          execute,
        }),
      );

      expect(result.current.getQuery()).toEqual({});

      act(() => {
        result.current.setQuery(newQuery);
      });

      expect(result.current.getQuery()).toEqual(newQuery);
    });

    it('should handle complex nested object queries', () => {
      const initialQuery = {
        user: { id: 1, name: 'John' },
        filters: { active: true, tags: ['a', 'b'] },
        pagination: { page: 1, limit: 10 },
      };
      const newQuery = {
        ...initialQuery,
        pagination: { page: 2, limit: 10 },
      };
      const execute = vi.fn().mockResolvedValue(undefined);

      const { result } = renderHook(() =>
        useQueryState({
          initialQuery,
          execute,
        }),
      );

      expect(result.current.getQuery()).toEqual(initialQuery);

      act(() => {
        result.current.setQuery(newQuery);
      });

      expect(result.current.getQuery()).toEqual(newQuery);
    });

    it('should handle function dependencies change', async () => {
      const initialQuery = { id: 'test' };
      let callCount = 0;
      const execute1 = vi.fn().mockImplementation(() => {
        callCount++;
        return Promise.resolve();
      });
      const execute2 = vi.fn().mockImplementation(() => {
        callCount++;
        return Promise.resolve();
      });

      const { result, rerender } = renderHook(
        ({ execute }) =>
          useQueryState({
            initialQuery,
            autoExecute: true,
            execute,
          }),
        { initialProps: { execute: execute1 } },
      );

      await vi.waitFor(() => {
        expect(callCount).toBe(1);
      });

      // Change execute function
      rerender({ execute: execute2 });

      act(() => {
        result.current.setQuery({ id: 'updated' });
      });

      await vi.waitFor(() => {
        expect(callCount).toBe(3); // 1 initial + 1 on rerender + 1 on setQuery
        expect(execute2).toHaveBeenCalledWith({ id: 'updated' });
      });
    });

    it('should handle autoExecute change from false to true', async () => {
      const initialQuery = { id: 'test' };
      const execute = vi.fn().mockResolvedValue(undefined);

      const { result, rerender } = renderHook(
        ({ autoExecute }) =>
          useQueryState({
            initialQuery,
            autoExecute,
            execute,
          }),
        { initialProps: { autoExecute: false } },
      );

      expect(execute).not.toHaveBeenCalled();

      // Change to true
      rerender({ autoExecute: true });

      await vi.waitFor(() => {
        expect(execute).toHaveBeenCalledWith(initialQuery);
      });

      execute.mockClear();

      act(() => {
        result.current.setQuery({ id: 'updated' });
      });

      await vi.waitFor(() => {
        expect(execute).toHaveBeenCalledWith({ id: 'updated' });
      });
    });

    it('should handle very large query objects', () => {
      const largeQuery = {
        data: Array.from({ length: 1000 }, (_, i) => ({
          id: i,
          value: `item${i}`,
        })),
        metadata: { total: 1000, page: 1 },
      };
      const execute = vi.fn().mockResolvedValue(undefined);

      const { result } = renderHook(() =>
        useQueryState({
          initialQuery: largeQuery,
          execute,
        }),
      );

      expect(result.current.getQuery()).toEqual(largeQuery);

      const newLargeQuery = {
        ...largeQuery,
        metadata: { total: 1000, page: 2 },
      };

      act(() => {
        result.current.setQuery(newLargeQuery);
      });

      expect(result.current.getQuery()).toEqual(newLargeQuery);
    });
  });

  describe('memory and performance', () => {
    it('should maintain stable function references', () => {
      const initialQuery = { id: 'test' };
      const execute = vi.fn().mockResolvedValue(undefined);

      const { result, rerender } = renderHook(() =>
        useQueryState({
          initialQuery,
          execute,
        }),
      );

      const firstGetQuery = result.current.getQuery;
      const firstSetQuery = result.current.setQuery;

      rerender();

      expect(result.current.getQuery).toBe(firstGetQuery);
      expect(result.current.setQuery).toBe(firstSetQuery);
    });

    it('should not cause unnecessary re-executions on rerender', async () => {
      const initialQuery = { id: 'test' };
      const execute = vi.fn().mockResolvedValue(undefined);

      const { rerender } = renderHook(() =>
        useQueryState({
          initialQuery,
          autoExecute: true,
          execute,
        }),
      );

      await vi.waitFor(() => {
        expect(execute).toHaveBeenCalledTimes(1);
      });

      // Rerender without changing props
      rerender();

      // Should not execute again
      expect(execute).toHaveBeenCalledTimes(1);
    });
  });
});
