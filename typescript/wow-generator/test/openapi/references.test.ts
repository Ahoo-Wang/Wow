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
import {
  findDanglingReferences,
  isReference,
  resolveLocalPointer,
} from '../../src/openapi/references';
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

  describe('findDanglingReferences', () => {
    it('reports a component reference that points at nothing, with where it is', () => {
      expect(
        findDanglingReferences({
          paths: {
            '/a': {
              get: {
                parameters: [{ $ref: '#/components/parameters/Missing' }],
              },
            },
          },
          components: {
            parameters: {},
            schemas: {
              A: { $ref: '#/components/schemas/B' },
              B: { properties: { c: { $ref: '#/components/schemas/C~1D' } } },
              'C/D': { type: 'string' },
            },
          },
        }),
      ).toEqual([
        {
          ref: '#/components/parameters/Missing',
          location: '/paths/~1a/get/parameters/0',
        },
      ]);
    });

    // Wow 8.11 writes its filter schema with JSON Schema definitions that it
    // references relative to the schema itself.
    it('leaves references that are not to components alone', () => {
      expect(
        findDanglingReferences({
          components: {
            schemas: {
              Filter: {
                $ref: '#/definitions/filter',
                definitions: { filter: { type: 'object' } },
              },
              Remote: { $ref: 'common.yaml#/components/schemas/Remote' },
            },
          },
        }),
      ).toEqual([]);
    });
  });

  describe('resolveLocalPointer', () => {
    it('decodes escaped and percent-encoded tokens', () => {
      const document = { a: { 'b/c': { 'd~e': 1, 'f g': 2 } } };
      expect(resolveLocalPointer(document, '#/a/b~1c/d~0e')).toBe(1);
      expect(resolveLocalPointer(document, '#/a/b~1c/f%20g')).toBe(2);
      expect(resolveLocalPointer(document, '#')).toBe(document);
      expect(resolveLocalPointer(document, '#/a/missing')).toBeUndefined();
      expect(resolveLocalPointer(document, '#/a/b~1c/d~0e/deeper')).toBe(
        undefined,
      );
    });
  });
});
