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
import { isReference } from '@/utils/references.ts';
import { Reference } from '@ahoo-wang/fetcher-openapi';

describe('references', () => {
  describe('isReference', () => {
    it('should return true for objects with $ref property', () => {
      const reference: Reference = { $ref: '#/components/schemas/User' };
      expect(isReference(reference)).toBe(true);
    });

    it('should return false for objects without $ref property', () => {
      const schema = { type: 'object' };
      expect(isReference(schema)).toBe(false);
    });

    it('should return false for null', () => {
      expect(isReference(null)).toBe(false);
    });

    it('should return false for undefined', () => {
      expect(isReference(undefined)).toBe(false);
    });

    it('should return false for primitives', () => {
      expect(isReference('string')).toBe(false);
      expect(isReference(42)).toBe(false);
      expect(isReference(true)).toBe(false);
    });
  });
});
