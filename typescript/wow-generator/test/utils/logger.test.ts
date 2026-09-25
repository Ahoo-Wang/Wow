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

import { describe, expect, it, vi } from 'vitest';
import { WarningCounter } from '../../src/utils/logger';

describe('WarningCounter', () => {
  it('forwards every call and counts the warnings', () => {
    const delegate = {
      debug: vi.fn(),
      info: vi.fn(),
      warn: vi.fn(),
      error: vi.fn(),
    };
    const counter = new WarningCounter(delegate);

    counter.debug('d', 0);
    counter.info('i', 1);
    counter.warn('w1');
    counter.warn('w2', 2);
    counter.error('e');

    expect(counter.warnings).toBe(2);
    expect(delegate.debug).toHaveBeenCalledWith('d', 0);
    expect(delegate.info).toHaveBeenCalledWith('i', 1);
    expect(delegate.warn).toHaveBeenCalledWith('w1');
    expect(delegate.warn).toHaveBeenCalledWith('w2', 2);
    expect(delegate.error).toHaveBeenCalledWith('e');
  });
});
