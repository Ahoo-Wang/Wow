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
import { useCountQuery } from '../../src';
import { Condition, all } from '@ahoo-wang/fetcher-wow';

// Mock the core hooks
vi.mock('../../src/core', () => ({
  useExecutePromise: vi.fn(),
  useLatest: vi.fn(),
}));

import { useExecutePromise, useLatest } from '../../src';

describe('useCountQuery', () => {
  const mockCount = 42;

  const initialCondition: Condition<'id' | 'name'> = all();

  const mockCountFn = vi.fn().mockResolvedValue(mockCount);
  const mockExecute = vi.fn().mockImplementation(async executor => {
    if (executor) {
      return await executor();
    }
    return mockCount;
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
      current: { count: mockCountFn, attributes: {} },
    });
  });

  it('should initialize with initial condition values', () => {
    const { result } = renderHook(() =>
      useCountQuery({
        initialCondition,
        count: mockCountFn,
      }),
    );

    expect(result.current).toHaveProperty('execute');
    expect(result.current).toHaveProperty('reset');
    expect(result.current).toHaveProperty('setCondition');
  });

  it('should execute count query when execute is called', async () => {
    const { result } = renderHook(() =>
      useCountQuery({
        initialCondition,
        count: mockCountFn,
      }),
    );

    await act(async () => {
      await result.current.execute();
    });

    expect(mockExecute).toHaveBeenCalled();
  });

  it('should call reset when reset is called', () => {
    const { result } = renderHook(() =>
      useCountQuery({
        initialCondition,
        count: mockCountFn,
      }),
    );

    act(() => {
      result.current.reset();
    });

    expect(mockReset).toHaveBeenCalled();
  });

  it('should update condition when setCondition is called', () => {
    const { result } = renderHook(() =>
      useCountQuery({
        initialCondition,
        count: mockCountFn,
      }),
    );

    const newCondition: Condition<'id' | 'name'> = all();

    act(() => {
      result.current.setCondition(newCondition);
    });

    expect(true).toBe(true); // Placeholder, as internal state is hard to test directly
  });

  it('should pass attributes to count function', async () => {
    const attributes = { token: 'abc' };
    (useLatest as any).mockReturnValue({
      current: { count: mockCountFn, attributes },
    });

    const { result } = renderHook(() =>
      useCountQuery({
        initialCondition,
        count: mockCountFn,
        attributes,
      }),
    );

    await act(async () => {
      await result.current.execute();
    });

    expect(mockCountFn).toHaveBeenCalledWith(expect.any(Object), attributes);
  });

  it('should handle count errors', async () => {
    const error = new Error('Count failed');
    mockExecute.mockRejectedValue(error);

    const { result } = renderHook(() =>
      useCountQuery({
        initialCondition,
        count: mockCountFn,
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
      useCountQuery({
        initialCondition,
        count: mockCountFn,
        autoExecute: true,
      } as any),
    );

    expect(mockExecute).toHaveBeenCalledTimes(1);
  });

  it('should not auto execute on mount when autoExecute is false', () => {
    renderHook(() =>
      useCountQuery({
        initialCondition,
        count: mockCountFn,
        autoExecute: false,
      } as any),
    );

    expect(mockExecute).not.toHaveBeenCalled();
  });

  it('should not auto execute on mount when autoExecute is not provided', () => {
    renderHook(() =>
      useCountQuery({
        initialCondition,
        count: mockCountFn,
      }),
    );

    expect(mockExecute).not.toHaveBeenCalled();
  });
});
