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
  addJSDoc,
  addMainSchemaJSDoc,
  addSchemaJSDoc,
  jsDoc,
} from '../../src/emit/jsdoc';

/** A declaration structure the doc comments are added to. */
type Documented = { docs?: string[] };

describe('jsdoc', () => {
  describe('jsDoc', () => {
    it('should return undefined for empty inputs', () => {
      expect(jsDoc([''])).toBeUndefined();
      expect(jsDoc([undefined, undefined])).toBeUndefined();
    });

    it('should return title only', () => {
      expect(jsDoc(['Title'])).toBe('Title');
    });

    it('should return description only', () => {
      expect(jsDoc([undefined, 'Description'])).toBe('Description');
    });

    it('should join title and description with newline', () => {
      expect(jsDoc(['Title', 'Description'])).toBe('Title\nDescription');
    });

    it('should filter out empty strings', () => {
      expect(jsDoc(['Title', ''])).toBe('Title');
      expect(jsDoc(['', 'Description'])).toBe('Description');
    });

    it('should return undefined for non-array input', () => {
      expect(jsDoc('string' as any)).toBeUndefined();
      expect(jsDoc(null as any)).toBeUndefined();
      expect(jsDoc(123 as any)).toBeUndefined();
      expect(jsDoc({} as any)).toBeUndefined();
    });

    it('should filter out non-string values', () => {
      expect(jsDoc(['string', null as any, 123 as any, undefined])).toBe(
        'string',
      );
      expect(jsDoc([null as any, 456 as any, 'valid'])).toBe('valid');
    });
  });

  describe('addJSDoc', () => {
    it('should not add jsdoc if no content', () => {
      const mockNode: Documented = {};

      addJSDoc(mockNode, ['', '']);

      expect(mockNode.docs).toBeUndefined();
    });

    it('should add jsdoc with title and description', () => {
      const mockNode: Documented = {};

      addJSDoc(mockNode, ['Title', 'Description']);

      expect(mockNode.docs).toEqual(['Title\nDescription']);
    });

    it('should add jsdoc with title only', () => {
      const mockNode: Documented = {};

      addJSDoc(mockNode, ['Title']);

      expect(mockNode.docs).toEqual(['Title']);
    });
  });

  describe('addSchemaJSDoc', () => {
    it('should add JSDoc with schema title and description', () => {
      const mockNode: Documented = {};
      const schema = {
        title: 'Test Title',
        description: 'Test Description',
      };

      addSchemaJSDoc(mockNode, schema as any);

      expect(mockNode.docs).toEqual(['Test Title\nTest Description']);
    });

    it('should handle schema with only title', () => {
      const mockNode: Documented = {};
      const schema = {
        title: 'Test Title',
      };

      addSchemaJSDoc(mockNode, schema as any);

      expect(mockNode.docs).toEqual(['Test Title']);
    });

    it('should handle schema with only description', () => {
      const mockNode: Documented = {};
      const schema = {
        description: 'Test Description',
      };

      addSchemaJSDoc(mockNode, schema as any);

      expect(mockNode.docs).toEqual(['Test Description']);
    });

    it('should not add JSDoc if schema has no title or description', () => {
      const mockNode: Documented = {};
      const schema = {};

      addSchemaJSDoc(mockNode, schema as any);

      expect(mockNode.docs).toBeUndefined();
    });

    it('should include format in JSDoc', () => {
      const mockNode: Documented = {};
      const schema = {
        title: 'Test Title',
        format: 'date-time',
      };

      addSchemaJSDoc(mockNode, schema as any);

      expect(mockNode.docs).toEqual(['Test Title\n- format: date-time']);
    });

    it('should include default value in JSDoc', () => {
      const mockNode: Documented = {};
      const schema = {
        title: 'Test Title',
        default: 'default-value',
      };

      addSchemaJSDoc(mockNode, schema as any);

      expect(mockNode.docs).toEqual(['Test Title\n- default: `default-value`']);
    });

    it('should include example as JSON in JSDoc', () => {
      const mockNode: Documented = {};
      const schema = {
        title: 'Test Title',
        example: { key: 'value' },
      };

      addSchemaJSDoc(mockNode, schema as any);

      expect(mockNode.docs).toEqual([
        'Test Title\n- example: \n```json\n{\n  "key": "value"\n}\n```',
      ]);
    });

    it('should include numeric constraints in JSDoc', () => {
      const mockNode: Documented = {};
      const schema = {
        title: 'Test Title',
        minimum: 0,
        maximum: 100,
        multipleOf: 5,
      };

      addSchemaJSDoc(mockNode, schema as any);

      expect(mockNode.docs).toEqual([
        'Test Title\n- Numeric Constraints\n  - minimum: 0\n  - maximum: 100\n  - multipleOf: 5',
      ]);
    });

    it('should include string constraints in JSDoc', () => {
      const mockNode: Documented = {};
      const schema = {
        title: 'Test Title',
        minLength: 1,
        maxLength: 50,
        pattern: '^[a-zA-Z]+$',
      };

      addSchemaJSDoc(mockNode, schema as any);

      expect(mockNode.docs).toEqual([
        'Test Title\n- String Constraints\n  - minLength: 1\n  - maxLength: 50\n  - pattern: ^[a-zA-Z]+$',
      ]);
    });

    it('should include array constraints in JSDoc', () => {
      const mockNode: Documented = {};
      const schema = {
        title: 'Test Title',
        minItems: 1,
        maxItems: 10,
        uniqueItems: true,
      };

      addSchemaJSDoc(mockNode, schema as any);

      expect(mockNode.docs).toEqual([
        'Test Title\n- Array Constraints\n  - minItems: 1\n  - maxItems: 10\n  - uniqueItems: true',
      ]);
    });

    it('should not include constraints sections when no constraints are present', () => {
      const mockNode: Documented = {};
      const schema = {
        title: 'Test Title',
        type: 'string',
      };

      addSchemaJSDoc(mockNode, schema as any);

      expect(mockNode.docs).toEqual(['Test Title']);
    });

    it('should handle exclusive minimum and maximum', () => {
      const mockNode: Documented = {};
      const schema = {
        title: 'Test Title',
        exclusiveMinimum: 0,
        exclusiveMaximum: 100,
      };

      addSchemaJSDoc(mockNode, schema as any);

      expect(mockNode.docs).toEqual([
        'Test Title\n- Numeric Constraints\n  - exclusiveMinimum: 0\n  - exclusiveMaximum: 100',
      ]);
    });

    it('documents a nullable value from its non-null branch', () => {
      const mockNode: Documented = {};

      addSchemaJSDoc(mockNode, {
        title: 'Gender',
        anyOf: [{ type: 'null' }, { type: 'integer', format: 'int32' }],
      });

      expect(mockNode.docs).toEqual(['Gender\n- format: int32']);
    });

    it('lets the schema win over its non-null branch', () => {
      const mockNode: Documented = {};

      addSchemaJSDoc(mockNode, {
        exclusiveMinimum: 0,
        oneOf: [
          { type: 'integer', format: 'int64', title: 'Branch', minimum: 1 },
          { type: 'null' },
        ],
        title: 'Shipped at',
      });

      expect(mockNode.docs).toEqual([
        'Shipped at\n- format: int64 (a value beyond Number.MAX_SAFE_INTEGER loses precision)\n- Numeric Constraints\n  - minimum: 1\n  - exclusiveMinimum: 0',
      ]);
    });

    it('does not read a branch that is a reference or one of several', () => {
      const mockNode: Documented = {};

      addSchemaJSDoc(mockNode, {
        title: 'Reference',
        anyOf: [{ type: 'null' }, { $ref: '#/components/schemas/Money' }],
      });
      addSchemaJSDoc(mockNode, {
        title: 'Several',
        anyOf: [
          { type: 'null' },
          { type: 'integer', format: 'int32' },
          { type: 'string', format: 'date' },
        ],
      });

      expect(mockNode.docs).toEqual(['Reference', 'Several']);
    });
  });

  describe('addMainSchemaJSDoc', () => {
    it('lists the properties the schema requires', () => {
      const mockNode: Documented = {};

      addMainSchemaJSDoc(
        mockNode,
        {
          type: 'object',
          properties: {
            alias: { type: 'string' },
            city: { type: 'string' },
            priceEnd: { anyOf: [{ type: 'null' }, { type: 'number' }] },
          },
          required: ['city', 'priceEnd'],
        },
        'crm.customer.DeliveryAddress',
      );

      expect(mockNode.docs).toEqual([
        '- key: crm.customer.DeliveryAddress\n- required: city, priceEnd',
      ]);
    });

    it('says when an object requires no property', () => {
      const mockNode: Documented = {};

      addMainSchemaJSDoc(mockNode, {
        type: 'object',
        properties: { alias: { type: 'string' } },
      });

      expect(mockNode.docs).toEqual(['- required: (none)']);
    });

    it('lists nothing for a schema without properties', () => {
      const mockNode: Documented = {};

      addMainSchemaJSDoc(mockNode, { type: 'string', title: 'Name' });

      expect(mockNode.docs).toEqual(['Name']);
    });
  });
});
