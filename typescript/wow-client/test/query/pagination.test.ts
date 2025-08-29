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

import { describe, expect, it } from 'vitest';
import { DEFAULT_PAGINATION, pagination } from '../../src';

describe('pagination', () => {
  it('should export DEFAULT_PAGINATION with correct values', () => {
    expect(DEFAULT_PAGINATION.index).toBe(1);
    expect(DEFAULT_PAGINATION.size).toBe(10);
  });

  it('should create pagination object with default values', () => {
    const result = pagination();
    expect(result.index).toBe(1);
    expect(result.size).toBe(10);
  });

  it('should create pagination object with custom index', () => {
    const result = pagination(2);
    expect(result.index).toBe(2);
    expect(result.size).toBe(10);
  });

  it('should create pagination object with custom index and size', () => {
    const result = pagination(3, 20);
    expect(result.index).toBe(3);
    expect(result.size).toBe(20);
  });
});
