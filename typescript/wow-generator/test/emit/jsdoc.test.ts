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

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { addJSDoc, addSchemaJSDoc, jsDoc } from '../../src/emit/jsdoc';

// Mock ts-morph
vi.mock('ts-morph', () => ({
  Project: vi.fn(),
  SourceFile: vi.fn(),
}));

const mockDeclaration = {
  addNamedImport: vi.fn(),
};

const mockSourceFile = {
  getImportDeclaration: vi.fn(),
  addImportDeclaration: vi.fn().mockReturnValue(mockDeclaration),
  addNamedImport: vi.fn(),
  getDirectoryPath: vi.fn().mockReturnValue('/src'),
};

describe('jsdoc', () => {
  beforeEach(() => {
    mockSourceFile.getImportDeclaration.mockClear();
    mockSourceFile.addImportDeclaration.mockClear();
    mockDeclaration.addNamedImport.mockClear();
    mockSourceFile.getDirectoryPath.mockClear();
  });

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
      const mockNode = {
        addJsDoc: vi.fn(),
      };

      addJSDoc(mockNode as any, ['', '']);

      expect(mockNode.addJsDoc).not.toHaveBeenCalled();
    });

    it('should add jsdoc with title and description', () => {
      const mockNode = {
        addJsDoc: vi.fn(),
      };

      addJSDoc(mockNode as any, ['Title', 'Description']);

      expect(mockNode.addJsDoc).toHaveBeenCalledWith('Title\nDescription');
    });

    it('should add jsdoc with title only', () => {
      const mockNode = {
        addJsDoc: vi.fn(),
      };

      addJSDoc(mockNode as any, ['Title']);

      expect(mockNode.addJsDoc).toHaveBeenCalledWith('Title');
    });
  });

  describe('addSchemaJSDoc', () => {
    it('should add JSDoc with schema title and description', () => {
      const mockNode = {
        addJsDoc: vi.fn(),
      };
      const schema = {
        title: 'Test Title',
        description: 'Test Description',
      };

      addSchemaJSDoc(mockNode as any, schema as any);

      expect(mockNode.addJsDoc).toHaveBeenCalledWith(
        'Test Title\nTest Description',
      );
    });

    it('should handle schema with only title', () => {
      const mockNode = {
        addJsDoc: vi.fn(),
      };
      const schema = {
        title: 'Test Title',
      };

      addSchemaJSDoc(mockNode as any, schema as any);

      expect(mockNode.addJsDoc).toHaveBeenCalledWith('Test Title');
    });

    it('should handle schema with only description', () => {
      const mockNode = {
        addJsDoc: vi.fn(),
      };
      const schema = {
        description: 'Test Description',
      };

      addSchemaJSDoc(mockNode as any, schema as any);

      expect(mockNode.addJsDoc).toHaveBeenCalledWith('Test Description');
    });

    it('should not add JSDoc if schema has no title or description', () => {
      const mockNode = {
        addJsDoc: vi.fn(),
      };
      const schema = {};

      addSchemaJSDoc(mockNode as any, schema as any);

      expect(mockNode.addJsDoc).not.toHaveBeenCalled();
    });

    it('should include format in JSDoc', () => {
      const mockNode = {
        addJsDoc: vi.fn(),
      };
      const schema = {
        title: 'Test Title',
        format: 'date-time',
      };

      addSchemaJSDoc(mockNode as any, schema as any);

      expect(mockNode.addJsDoc).toHaveBeenCalledWith(
        'Test Title\n- format: date-time',
      );
    });

    it('should include default value in JSDoc', () => {
      const mockNode = {
        addJsDoc: vi.fn(),
      };
      const schema = {
        title: 'Test Title',
        default: 'default-value',
      };

      addSchemaJSDoc(mockNode as any, schema as any);

      expect(mockNode.addJsDoc).toHaveBeenCalledWith(
        'Test Title\n- default: `default-value`',
      );
    });

    it('should include example as JSON in JSDoc', () => {
      const mockNode = {
        addJsDoc: vi.fn(),
      };
      const schema = {
        title: 'Test Title',
        example: { key: 'value' },
      };

      addSchemaJSDoc(mockNode as any, schema as any);

      expect(mockNode.addJsDoc).toHaveBeenCalledWith(
        'Test Title\n- example: \n```json\n{\n  "key": "value"\n}\n```',
      );
    });

    it('should include numeric constraints in JSDoc', () => {
      const mockNode = {
        addJsDoc: vi.fn(),
      };
      const schema = {
        title: 'Test Title',
        minimum: 0,
        maximum: 100,
        multipleOf: 5,
      };

      addSchemaJSDoc(mockNode as any, schema as any);

      expect(mockNode.addJsDoc).toHaveBeenCalledWith(
        'Test Title\n- Numeric Constraints\n  - minimum: 0\n  - maximum: 100\n  - multipleOf: 5',
      );
    });

    it('should include string constraints in JSDoc', () => {
      const mockNode = {
        addJsDoc: vi.fn(),
      };
      const schema = {
        title: 'Test Title',
        minLength: 1,
        maxLength: 50,
        pattern: '^[a-zA-Z]+$',
      };

      addSchemaJSDoc(mockNode as any, schema as any);

      expect(mockNode.addJsDoc).toHaveBeenCalledWith(
        'Test Title\n- String Constraints\n  - minLength: 1\n  - maxLength: 50\n  - pattern: ^[a-zA-Z]+$',
      );
    });

    it('should include array constraints in JSDoc', () => {
      const mockNode = {
        addJsDoc: vi.fn(),
      };
      const schema = {
        title: 'Test Title',
        minItems: 1,
        maxItems: 10,
        uniqueItems: true,
      };

      addSchemaJSDoc(mockNode as any, schema as any);

      expect(mockNode.addJsDoc).toHaveBeenCalledWith(
        'Test Title\n- Array Constraints\n  - minItems: 1\n  - maxItems: 10\n  - uniqueItems: true',
      );
    });

    it('should not include constraints sections when no constraints are present', () => {
      const mockNode = {
        addJsDoc: vi.fn(),
      };
      const schema = {
        title: 'Test Title',
        type: 'string',
      };

      addSchemaJSDoc(mockNode as any, schema as any);

      expect(mockNode.addJsDoc).toHaveBeenCalledWith('Test Title');
    });

    it('should handle exclusive minimum and maximum', () => {
      const mockNode = {
        addJsDoc: vi.fn(),
      };
      const schema = {
        title: 'Test Title',
        exclusiveMinimum: 0,
        exclusiveMaximum: 100,
      };

      addSchemaJSDoc(mockNode as any, schema as any);

      expect(mockNode.addJsDoc).toHaveBeenCalledWith(
        'Test Title\n- Numeric Constraints\n  - exclusiveMinimum: 0\n  - exclusiveMaximum: 100',
      );
    });
  });
});
