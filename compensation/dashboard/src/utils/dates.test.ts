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
import { formatDate } from './dates';

describe('dates', () => {
  describe('formatDate', () => {
    it('should format timestamp correctly with default template', () => {
      const timestamp = new Date('2023-01-01 12:00:00').getTime();
      const formatted = formatDate(timestamp);
      expect(formatted).toBe('2023-01-01 12:00:00');
    });

    it('should format timestamp with custom template', () => {
      const timestamp = new Date('2023-01-01 12:00:00').getTime();
      const formatted = formatDate(timestamp, 'YYYY-MM-DD');
      expect(formatted).toBe('2023-01-01');
    });

    it('should return "-" when timeAt is undefined', () => {
      const formatted = formatDate(undefined);
      expect(formatted).toBe('-');
    });

    it('should return "-" when timeAt is 0', () => {
      const formatted = formatDate(0);
      expect(formatted).toBe('-');
    });

    it('should format timestamp correctly with different time values', () => {
      const timestamp = new Date('2022-12-25 08:30:45').getTime();
      const formatted = formatDate(timestamp, 'YYYY/MM/DD HH:mm');
      expect(formatted).toBe('2022/12/25 08:30');
    });
  });
});