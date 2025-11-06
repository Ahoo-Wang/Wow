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
import { TypeGenerator } from '../../src/model';
import { ModelInfo } from '../../src/model';
import {
  addMainSchemaJSDoc,
} from '../../src/utils';

// Mock the sourceFiles module
vi.mock('../../src/utils/sourceFiles', () => ({
  addImportModelInfo: vi.fn(),
  addSchemaJSDoc: vi.fn(),
  addMainSchemaJSDoc: vi.fn(),
  addImport: vi.fn(),
  schemaJSDoc: vi.fn(() => []),
  jsDoc: vi.fn(() => ''),
}));

describe('TypeGenerator', () => {
  const modelInfo: ModelInfo = {
    name: 'TestModel',
    path: '/test',
  };

  const outputDir = '/output';

  describe('resolveType', () => {
    it('should resolve primitive string type', () => {
      const generator = new TypeGenerator(
        modelInfo,
        {} as any,
        {} as any,
        outputDir,
      );
      const result = (generator as any).resolveType({ type: 'string' });
      expect(result).toBe('string');
    });

    it('should resolve primitive number type', () => {
      const generator = new TypeGenerator(
        modelInfo,
        {} as any,
        {} as any,
        outputDir,
      );
      const result = (generator as any).resolveType({ type: 'number' });
      expect(result).toBe('number');
    });

    it('should resolve primitive boolean type', () => {
      const generator = new TypeGenerator(
        modelInfo,
        {} as any,
        {} as any,
        outputDir,
      );
      const result = (generator as any).resolveType({ type: 'boolean' });
      expect(result).toBe('boolean');
    });

    it('should resolve const type', () => {
      const generator = new TypeGenerator(
        modelInfo,
        {} as any,
        {} as any,
        outputDir,
      );
      const result = (generator as any).resolveType({ const: 'fixedValue' });
      expect(result).toBe("'fixedValue'");
    });

    it('should resolve enum type', () => {
      const generator = new TypeGenerator(
        modelInfo,
        {} as any,
        {} as any,
        outputDir,
      );
      const result = (generator as any).resolveType({
        type: 'string',
        enum: ['a', 'b', 'c'],
      });
      expect(result).toBe("'a' | 'b' | 'c'");
    });

    it('should resolve array type', () => {
      const generator = new TypeGenerator(
        modelInfo,
        {} as any,
        {} as any,
        outputDir,
      );
      const result = (generator as any).resolveType({
        type: 'array',
        items: { type: 'string' },
      });
      expect(result).toBe('string[]');
    });

    it('should resolve object type with properties', () => {
      const generator = new TypeGenerator(
        modelInfo,
        {} as any,
        {} as any,
        outputDir,
      );
      const result = (generator as any).resolveType({
        type: 'object',
        properties: { name: { type: 'string' }, age: { type: 'number' } },
      });
      expect(result).toBe('{\n  name: string;\n  age: number; \n}');
    });

    it('should resolve object type with additional properties', () => {
      const generator = new TypeGenerator(
        modelInfo,
        {} as any,
        {} as any,
        outputDir,
      );
      const result = (generator as any).resolveType({
        type: 'object',
        additionalProperties: { type: 'string' },
      });
      expect(result).toBe('Record<string,string>');
    });

    it('should resolve composition oneOf type', () => {
      const generator = new TypeGenerator(
        modelInfo,
        {} as any,
        {} as any,
        outputDir,
      );
      const result = (generator as any).resolveType({
        oneOf: [{ type: 'string' }, { type: 'number' }],
      });
      expect(result).toBe('(string | number)');
    });

    it('should resolve composition allOf type', () => {
      const generator = new TypeGenerator(
        modelInfo,
        {} as any,
        {} as any,
        outputDir,
      );
      const result = (generator as any).resolveType({
        allOf: [{ type: 'string' }, { type: 'number' }],
      });
      expect(result).toBe('(string & number)');
    });

    it('should return any for unknown type', () => {
      const generator = new TypeGenerator(
        modelInfo,
        {} as any,
        {} as any,
        outputDir,
      );
      const result = (generator as any).resolveType({});
      expect(result).toBe('any');
    });
  });

  describe('resolveObjectType', () => {
    it('should resolve object with properties only', () => {
      const generator = new TypeGenerator(
        modelInfo,
        {} as any,
        {} as any,
        outputDir,
      );
      const result = (generator as any).resolveObjectType({
        type: 'object',
        properties: { name: { type: 'string' } },
      });
      expect(result).toBe('{\n  name: string; \n}');
    });

    it('should resolve object with additional properties only', () => {
      const generator = new TypeGenerator(
        modelInfo,
        {} as any,
        {} as any,
        outputDir,
      );
      const result = (generator as any).resolveType({
        type: 'object',
        additionalProperties: { type: 'string' },
      });
      expect(result).toBe('Record<string,string>');
    });

    it('should resolve object with both properties and additional properties', () => {
      const generator = new TypeGenerator(
        modelInfo,
        {} as any,
        {} as any,
        outputDir,
      );
      const result = (generator as any).resolveObjectType({
        type: 'object',
        properties: { name: { type: 'string' } },
        additionalProperties: { type: 'number' },
      });
      expect(result).toBe('{\n  name: string;\n  [key: string]: number; \n}');
    });

    it('should return Record<string, any> for empty object', () => {
      const generator = new TypeGenerator(
        modelInfo,
        {} as any,
        {} as any,
        outputDir,
      );
      const result = generator.resolveType({ type: 'object' });
      expect(result).toBe('Record<string, any>');
    });
  });

  describe('resolveAdditionalProperties', () => {
    it('should return empty string for undefined additionalProperties', () => {
      const generator = new TypeGenerator(
        modelInfo,
        {} as any,
        {} as any,
        outputDir,
      );
      const result = (generator as any).resolveAdditionalProperties({});
      expect(result).toBe('');
    });

    it('should return empty string for false additionalProperties', () => {
      const generator = new TypeGenerator(
        modelInfo,
        {} as any,
        {} as any,
        outputDir,
      );
      const result = (generator as any).resolveAdditionalProperties({
        additionalProperties: false,
      });
      expect(result).toBe('');
    });

    it('should return any type for true additionalProperties', () => {
      const generator = new TypeGenerator(
        modelInfo,
        {} as any,
        {} as any,
        outputDir,
      );
      const result = (generator as any).resolveAdditionalProperties({
        additionalProperties: true,
      });
      expect(result).toBe('[key: string]: any');
    });

    it('should resolve schema type for additionalProperties', () => {
      const generator = new TypeGenerator(
        modelInfo,
        {} as any,
        {} as any,
        outputDir,
      );
      const result = (generator as any).resolveAdditionalProperties({
        additionalProperties: { type: 'string' },
      });
      expect(result).toBe('[key: string]: string');
    });
  });

  describe('resolvePropertyDefinitions', () => {
    it('should format property definitions correctly', () => {
      const generator = new TypeGenerator(
        modelInfo,
        {} as any,
        {} as any,
        outputDir,
      );
      const result = (generator as any).resolvePropertyDefinitions({
        type: 'object',
        properties: {
          name: { type: 'string' },
          age: { type: 'number' },
        },
      });
      expect(result).toEqual(['name: string', 'age: number']);
    });
  });

  describe('process', () => {
    it('should process enum schema', () => {
      const mockSourceFile = {
        addEnum: vi.fn().mockReturnValue({ addJsDoc: vi.fn() }),
      };
      const generator = new TypeGenerator(
        modelInfo,
        mockSourceFile as any,
        {
          key: 'TestEnum',
          schema: { type: 'string', enum: ['value1', 'value2'] },
        },
        outputDir,
      );

      const result = (generator as any).process();
      expect(mockSourceFile.addEnum).toHaveBeenCalledWith({
        name: 'TestModel',
        isExported: true,
        members: [
          { name: 'VALUE1', initializer: "'value1'" },
          { name: 'VALUE2', initializer: "'value2'" },
        ],
      });
      expect(result).toBeDefined();
    });

    it('should process object schema', () => {
      const mockSourceFile = {
        addInterface: vi.fn().mockReturnValue({
          addProperty: vi.fn(),
          getProperty: vi.fn().mockReturnValue(null),
        }),
      };
      const generator = new TypeGenerator(
        modelInfo,
        mockSourceFile as any,
        {
          key: 'TestInterface',
          schema: {
            type: 'object',
            properties: { id: { type: 'string' } },
          },
        },
        outputDir,
      );

      const result = (generator as any).process();
      expect(mockSourceFile.addInterface).toHaveBeenCalledWith({
        name: 'TestModel',
        isExported: true,
      });
      expect(result).toBeDefined();
    });

    it('should process array schema', () => {
      const mockSourceFile = {
        addTypeAlias: vi.fn().mockReturnValue({ addJsDoc: vi.fn() }),
      };
      const generator = new TypeGenerator(
        modelInfo,
        mockSourceFile as any,
        {
          key: 'TestArray',
          schema: { type: 'array', items: { type: 'string' } },
        },
        outputDir,
      );

      const result = (generator as any).process();
      expect(mockSourceFile.addTypeAlias).toHaveBeenCalledWith({
        name: 'TestModel',
        type: 'Array<string>',
        isExported: true,
      });
      expect(result).toBeDefined();
    });

    it('should process composition schema', () => {
      const mockSourceFile = {
        addTypeAlias: vi.fn().mockReturnValue({ addJsDoc: vi.fn() }),
      };
      const generator = new TypeGenerator(
        modelInfo,
        mockSourceFile as any,
        {
          key: 'TestComposition',
          schema: { oneOf: [{ type: 'string' }, { type: 'number' }] },
        },
        outputDir,
      );

      const result = (generator as any).process();
      expect(mockSourceFile.addTypeAlias).toHaveBeenCalledWith({
        name: 'TestModel',
        type: '(string | number)',
        isExported: true,
      });
      expect(result).toBeDefined();
    });

    it('should process allOf schema', () => {
      const mockSourceFile = {
        addInterface: vi.fn().mockReturnValue({
          addProperty: vi.fn(),
          getProperty: vi.fn().mockReturnValue(null),
          addExtends: vi.fn(),
        }),
        getDirectoryPath: vi.fn().mockReturnValue('/output'),
        getImportDeclaration: vi.fn().mockReturnValue(null),
        addImportDeclaration: vi.fn().mockReturnValue({
          getNamedImports: vi.fn().mockReturnValue([]),
          addNamedImport: vi.fn(),
        }),
        getNamedImports: vi.fn().mockReturnValue([]),
      };
      const generator = new TypeGenerator(
        modelInfo,
        mockSourceFile as any,
        {
          key: 'TestAllOf',
          schema: {
            allOf: [
              { $ref: '#/components/schemas/BaseModel' },
              { type: 'object', properties: { extra: { type: 'boolean' } } },
            ],
          },
        },
        outputDir,
      );

      const result = (generator as any).process();
      expect(mockSourceFile.addInterface).toHaveBeenCalledWith({
        name: 'TestModel',
        isExported: true,
      });
      expect(result).toBeDefined();
    });

    it('should process map schema', () => {
      const mockSourceFile = {
        addTypeAlias: vi.fn().mockReturnValue({ addJsDoc: vi.fn() }),
      };
      const generator = new TypeGenerator(
        modelInfo,
        mockSourceFile as any,
        {
          key: 'TestMap',
          schema: {
            type: 'object',
            additionalProperties: { type: 'string' },
          },
        },
        outputDir,
      );

      const result = (generator as any).process();
      expect(mockSourceFile.addTypeAlias).toHaveBeenCalledWith({
        name: 'TestModel',
        type: 'Record<string,string>',
        isExported: true,
      });
      expect(result).toBeDefined();
    });

    it('should process type alias for primitive types', () => {
      const mockSourceFile = {
        addTypeAlias: vi.fn().mockReturnValue({ addJsDoc: vi.fn() }),
      };
      const generator = new TypeGenerator(
        modelInfo,
        mockSourceFile as any,
        {
          key: 'TestPrimitive',
          schema: { type: 'boolean' },
        },
        outputDir,
      );

      const result = (generator as any).process();
      expect(mockSourceFile.addTypeAlias).toHaveBeenCalledWith({
        name: 'TestModel',
        type: 'boolean',
        isExported: true,
      });
      expect(result).toBeDefined();
    });
  });

  describe('generate', () => {
    it('should call process and add JSDoc when node is returned', () => {
      const mockNode = {
        addJsDoc: vi.fn(),
        getProperty: vi.fn().mockReturnValue(null),
        addProperty: vi.fn(),
      };
      const mockSourceFile = {
        addInterface: vi.fn().mockReturnValue(mockNode),
      };
      const generator = new TypeGenerator(
        modelInfo,
        mockSourceFile as any,
        {
          key: 'TestModel',
          schema: {
            type: 'object',
            properties: { id: { type: 'string' } },
          },
        },
        outputDir,
      );

      generator.generate();
      expect(mockSourceFile.addInterface).toHaveBeenCalled();
      expect(addMainSchemaJSDoc).toHaveBeenCalledWith(
        mockNode,
        {
          type: 'object',
          properties: { id: { type: 'string' } },
        },
        'TestModel',
      );
    });

    it('should call process but not add JSDoc when node is undefined', () => {
      const mockSourceFile = {
        addTypeAlias: vi.fn().mockReturnValue(undefined),
      };
      const generator = new TypeGenerator(
        modelInfo,
        mockSourceFile as any,
        {
          key: 'TestModel',
          schema: { type: 'string' },
        },
        outputDir,
      );

      generator.generate();
      expect(mockSourceFile.addTypeAlias).toHaveBeenCalled();
    });
  });

  describe('addPropertyToInterface', () => {
    it('should update existing property type when property already exists', () => {
      const mockPropertySignature = {
        setType: vi.fn(),
      };
      const mockInterfaceDeclaration = {
        getProperty: vi.fn().mockReturnValue(mockPropertySignature),
        addProperty: vi.fn(),
      };
      const generator = new TypeGenerator(
        modelInfo,
        {} as any,
        {} as any,
        outputDir,
      );

      (generator as any).addPropertyToInterface(
        mockInterfaceDeclaration as any,
        'existingProp',
        { type: 'string' },
      );

      expect(mockInterfaceDeclaration.getProperty).toHaveBeenCalledWith(
        'existingProp',
      );
      expect(mockPropertySignature.setType).toHaveBeenCalledWith('string');
      expect(mockInterfaceDeclaration.addProperty).not.toHaveBeenCalled();
    });
  });

  describe('processInterface', () => {
    it('should add index signature when additionalProperties is true', () => {
      const mockIndexSignature = {
        addJsDoc: vi.fn(),
      };
      const mockInterfaceDeclaration = {
        getProperty: vi.fn().mockReturnValue(null),
        addProperty: vi.fn(),
        addIndexSignature: vi.fn().mockReturnValue(mockIndexSignature),
      };
      const mockSourceFile = {
        addInterface: vi.fn().mockReturnValue(mockInterfaceDeclaration),
      };
      const generator = new TypeGenerator(
        modelInfo,
        mockSourceFile as any,
        {
          key: 'TestModel',
          schema: {
            type: 'object',
            properties: { id: { type: 'string' } },
            additionalProperties: true,
          },
        },
        outputDir,
      );

      const result = (generator as any).processInterface({
        type: 'object',
        properties: { id: { type: 'string' } },
        additionalProperties: true,
      });

      expect(mockInterfaceDeclaration.addIndexSignature).toHaveBeenCalledWith({
        keyName: 'key',
        keyType: 'string',
        returnType: 'any',
      });
      expect(mockIndexSignature.addJsDoc).toHaveBeenCalledWith(
        'Additional properties',
      );
      expect(result).toBe(mockInterfaceDeclaration);
    });

    it('should add index signature when additionalProperties is a schema', () => {
      const mockIndexSignature = {
        addJsDoc: vi.fn(),
      };
      const mockInterfaceDeclaration = {
        getProperty: vi.fn().mockReturnValue(null),
        addProperty: vi.fn(),
        addIndexSignature: vi.fn().mockReturnValue(mockIndexSignature),
      };
      const mockSourceFile = {
        addInterface: vi.fn().mockReturnValue(mockInterfaceDeclaration),
      };
      const generator = new TypeGenerator(
        modelInfo,
        mockSourceFile as any,
        {
          key: 'TestModel',
          schema: {
            type: 'object',
            properties: { id: { type: 'string' } },
            additionalProperties: { type: 'number' },
          },
        },
        outputDir,
      );

      const result = (generator as any).processInterface({
        type: 'object',
        properties: { id: { type: 'string' } },
        additionalProperties: { type: 'number' },
      });

      expect(mockInterfaceDeclaration.addIndexSignature).toHaveBeenCalledWith({
        keyName: 'key',
        keyType: 'string',
        returnType: 'number',
      });
      expect(mockIndexSignature.addJsDoc).toHaveBeenCalledWith(
        'Additional properties',
      );
      expect(result).toBe(mockInterfaceDeclaration);
    });
  });

  it('should add index signature when additionalProperties is a schema', () => {
    const mockIndexSignature = {
      addJsDoc: vi.fn(),
    };
    const mockInterfaceDeclaration = {
      getProperty: vi.fn().mockReturnValue(null),
      addProperty: vi.fn(),
      addIndexSignature: vi.fn().mockReturnValue(mockIndexSignature),
    };
    const mockSourceFile = {
      addInterface: vi.fn().mockReturnValue(mockInterfaceDeclaration),
    };
    const generator = new TypeGenerator(
      modelInfo,
      mockSourceFile as any,
      {
        key: 'TestModel',
        schema: {
          type: 'object',
          properties: { id: { type: 'string' } },
          additionalProperties: { type: 'number' },
        },
      },
      outputDir,
    );

    const result = (generator as any).processInterface({
      type: 'object',
      properties: { id: { type: 'string' } },
      additionalProperties: { type: 'number' },
    });

    expect(mockInterfaceDeclaration.addIndexSignature).toHaveBeenCalledWith({
      keyName: 'key',
      keyType: 'string',
      returnType: 'number',
    });
    expect(mockIndexSignature.addJsDoc).toHaveBeenCalledWith(
      'Additional properties',
    );
    expect(result).toBe(mockInterfaceDeclaration);
  });
});
