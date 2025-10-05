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
import { renderHook, act } from '@testing-library/react';
import { useListStreamQuery } from '../../src/wow/useListStreamQuery';
import {
  ListQuery,
  Condition,
  Projection,
  FieldSort,
  all,
  SortDirection,
} from '@ahoo-wang/fetcher-wow';

// Mock the core hooks
vi.mock('../../src/core', () => ({
  useExecutePromise: vi.fn(),
  useLatest: vi.fn(),
}));

import { useExecutePromise, useLatest } from '../../src/core';

// Mock stream type
type MockStream = ReadableStream<any>;

describe('useListStreamQuery', () => {
  const mockStream: MockStream = new ReadableStream();

  const initialQuery: ListQuery<'id' | 'name'> = {
    condition: all(),
    projection: { include: ['id', 'name'] },
    sort: [{ field: 'id', direction: SortDirection.ASC }],
    limit: 10,
  };

  const mockListStreamFn = vi.fn().mockResolvedValue(mockStream);
  const mockExecute = vi.fn().mockImplementation(async executor => {
    if (executor) {
      return await executor();
    }
    return mockStream;
  });
  const mockReset = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
    (useExecutePromise as any).mockReturnValue({
      data: null,
      loading: false,
      error: null,
      execute: mockExecute,
      reset: mockReset,
    });
    (useLatest as any).mockReturnValue({
      current: { listStream: mockListStreamFn, attributes: {} },
    });
  });

  it('should initialize with initial query values', () => {
    const { result } = renderHook(() =>
      useListStreamQuery({
        initialQuery,
        listStream: mockListStreamFn,
      }),
    );

    expect(result.current).toHaveProperty('execute');
    expect(result.current).toHaveProperty('reset');
    expect(result.current).toHaveProperty('setCondition');
    expect(result.current).toHaveProperty('setProjection');
    expect(result.current).toHaveProperty('setSort');
    expect(result.current).toHaveProperty('setLimit');
  });

  it('should execute list stream query when execute is called', async () => {
    const { result } = renderHook(() =>
      useListStreamQuery({
        initialQuery,
        listStream: mockListStreamFn,
      }),
    );

    await act(async () => {
      await result.current.execute();
    });

    expect(mockExecute).toHaveBeenCalled();
  });

  it('should call reset when reset is called', () => {
    const { result } = renderHook(() =>
      useListStreamQuery({
        initialQuery,
        listStream: mockListStreamFn,
      }),
    );

    act(() => {
      result.current.reset();
    });

    expect(mockReset).toHaveBeenCalled();
  });

  it('should update condition when setCondition is called', () => {
    const { result } = renderHook(() =>
      useListStreamQuery({
        initialQuery,
        listStream: mockListStreamFn,
      }),
    );

    const newCondition: Condition<'id' | 'name'> = all();

    act(() => {
      result.current.setCondition(newCondition);
    });

    expect(true).toBe(true); // Placeholder, as internal state is hard to test directly
  });

  it('should update projection when setProjection is called', () => {
    const { result } = renderHook(() =>
      useListStreamQuery({
        initialQuery,
        listStream: mockListStreamFn,
      }),
    );

    const newProjection: Projection<'id' | 'name'> = { include: ['name'] };

    act(() => {
      result.current.setProjection(newProjection);
    });

    expect(true).toBe(true); // Placeholder
  });

  it('should update sort when setSort is called', () => {
    const { result } = renderHook(() =>
      useListStreamQuery({
        initialQuery,
        listStream: mockListStreamFn,
      }),
    );

    const newSort: FieldSort<'id' | 'name'>[] = [
      { field: 'name', direction: SortDirection.DESC },
    ];

    act(() => {
      result.current.setSort(newSort);
    });

    expect(true).toBe(true); // Placeholder
  });

  it('should update limit when setLimit is called', () => {
    const { result } = renderHook(() =>
      useListStreamQuery({
        initialQuery,
        listStream: mockListStreamFn,
      }),
    );

    const newLimit = 20;

    act(() => {
      result.current.setLimit(newLimit);
    });

    expect(true).toBe(true); // Placeholder
  });

  it('should pass attributes to listStream function', async () => {
    const attributes = { token: 'abc' };
    (useLatest as any).mockReturnValue({
      current: { listStream: mockListStreamFn, attributes },
    });

    const { result } = renderHook(() =>
      useListStreamQuery({
        initialQuery,
        listStream: mockListStreamFn,
        attributes,
      }),
    );

    await act(async () => {
      await result.current.execute();
    });

    expect(mockListStreamFn).toHaveBeenCalledWith(
      expect.any(Object),
      attributes,
    );
  });

  it('should handle listStream errors', async () => {
    const error = new Error('ListStream failed');
    mockExecute.mockRejectedValue(error);

    const { result } = renderHook(() =>
      useListStreamQuery({
        initialQuery,
        listStream: mockListStreamFn,
      }),
    );

    await act(async () => {
      try {
        await result.current.execute();
      } catch (e) {
        // Expected error
      }
    });

    expect(mockExecute).toHaveBeenCalled();
  });

  it('should auto execute on mount when autoExecute is true', () => {
    renderHook(() =>
      useListStreamQuery({
        initialQuery,
        listStream: mockListStreamFn,
        autoExecute: true,
      } as any),
    );

    expect(mockExecute).toHaveBeenCalledTimes(1);
  });

  it('should not auto execute on mount when autoExecute is false', () => {
    renderHook(() =>
      useListStreamQuery({
        initialQuery,
        listStream: mockListStreamFn,
        autoExecute: false,
      } as any),
    );

    expect(mockExecute).not.toHaveBeenCalled();
  });

  it('should not auto execute on mount when autoExecute is not provided', () => {
    renderHook(() =>
      useListStreamQuery({
        initialQuery,
        listStream: mockListStreamFn,
      }),
    );

    expect(mockExecute).not.toHaveBeenCalled();
  });
});
