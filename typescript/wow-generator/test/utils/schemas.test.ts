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
import { isPrimitive, isEnum, resolvePrimitiveType } from '@/utils/schemas.ts';
import { SchemaType } from '@ahoo-wang/fetcher-openapi';

describe('schemas', () => {
  describe('isPrimitive', () => {
    it('should return true for primitive types', () => {
      expect(isPrimitive('string')).toBe(true);
      expect(isPrimitive('number')).toBe(true);
      expect(isPrimitive('integer')).toBe(true);
      expect(isPrimitive('boolean')).toBe(true);
      expect(isPrimitive('null')).toBe(true);
    });

    it('should return false for non-primitive types', () => {
      expect(isPrimitive('object')).toBe(false);
      expect(isPrimitive('array')).toBe(false);
    });

    it('should return false for array of types', () => {
      expect(isPrimitive(['string', 'number'])).toBe(false);
    });
  });

  describe('isEnum', () => {
    it('should return true for schema with enum', () => {
      const schema = { enum: ['a', 'b', 'c'] };
      expect(isEnum(schema)).toBe(true);
    });

    it('should return false for schema without enum', () => {
      const schema = { type: 'string' as SchemaType };
      expect(isEnum(schema)).toBe(false);
    });

    it('should return false for empty enum', () => {
      const schema = { enum: [] };
      expect(isEnum(schema)).toBe(false);
    });
  });

  describe('resolvePrimitiveType', () => {
    it('should resolve single primitive types', () => {
      expect(resolvePrimitiveType('string')).toBe('string');
      expect(resolvePrimitiveType('number')).toBe('number');
      expect(resolvePrimitiveType('integer')).toBe('number');
      expect(resolvePrimitiveType('boolean')).toBe('boolean');
      expect(resolvePrimitiveType('null')).toBe('null');
    });

    it('should return any for unknown types', () => {
      expect(resolvePrimitiveType('object' as SchemaType)).toBe('any');
    });

    it('should resolve array of types', () => {
      expect(resolvePrimitiveType(['string', 'number'])).toBe(
        'string | number',
      );
      expect(resolvePrimitiveType(['boolean', 'null'])).toBe('boolean | null');
    });
  });
});
