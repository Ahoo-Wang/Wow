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

import { StructureKind } from 'ts-morph';
import { describe, expect, it, vi } from 'vitest';
import { ImportRegistry } from '../../src/emit/importRegistry';
import { TypeGenerator } from '../../src/model';
import { ModelInfo } from '../../src/model';
import { addMainSchemaJSDoc } from '../../src/emit/jsdoc';

// Mock the emit helpers
vi.mock('../../src/emit/imports', () => ({
  addImportModelInfo: vi.fn(),
  addImport: vi.fn(),
}));
vi.mock('../../src/emit/jsdoc', () => ({
  addSchemaJSDoc: vi.fn(),
  addMainSchemaJSDoc: vi.fn(),
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
      expect(result).toBe('globalThis.Record<string,string>');
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
      expect(result).toBe('globalThis.Record<string,string>');
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
      expect(result).toBe(
        '({\n  name: string; \n} & globalThis.Record<string, number>)',
      );
    });

    it('should return globalThis.Record<string, any> for empty object', () => {
      const generator = new TypeGenerator(
        modelInfo,
        {} as any,
        {} as any,
        outputDir,
      );
      const result = generator.resolveType({ type: 'object' });
      expect(result).toBe('globalThis.Record<string, any>');
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

  /** A module that keeps what the generator adds, for the test to read. */
  function fakeModule() {
    return {
      add: vi.fn(statement => statement),
      imports: new ImportRegistry(),
      directoryPath: '/output',
    };
  }

  describe('process', () => {
    it('should process enum schema', () => {
      const module = fakeModule();
      const generator = new TypeGenerator(
        modelInfo,
        module as any,
        {
          key: 'TestEnum',
          schema: { type: 'string', enum: ['value1', 'value2'] },
        },
        outputDir,
      );

      const result = (generator as any).process();
      expect(module.add).toHaveBeenCalledWith({
        kind: StructureKind.Enum,
        name: 'TestModel',
        isExported: true,
        members: [
          { name: 'VALUE1', initializer: "'value1'" },
          { name: 'VALUE2', initializer: "'value2'" },
        ],
      });
      expect(result).toBe(module.add.mock.results[0].value);
    });

    it('should process object schema', () => {
      const module = fakeModule();
      const generator = new TypeGenerator(
        modelInfo,
        module as any,
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
      expect(module.add).toHaveBeenCalledWith({
        kind: StructureKind.Interface,
        name: 'TestModel',
        isExported: true,
        properties: [{ name: 'id', type: 'string', isReadonly: false }],
      });
      expect(result).toBe(module.add.mock.results[0].value);
    });

    it('should process array schema', () => {
      const module = fakeModule();
      const generator = new TypeGenerator(
        modelInfo,
        module as any,
        {
          key: 'TestArray',
          schema: { type: 'array', items: { type: 'string' } },
        },
        outputDir,
      );

      const result = (generator as any).process();
      expect(module.add).toHaveBeenCalledWith({
        kind: StructureKind.TypeAlias,
        name: 'TestModel',
        type: 'string[]',
        isExported: true,
      });
      expect(result).toBeDefined();
    });

    it('should process composition schema', () => {
      const module = fakeModule();
      const generator = new TypeGenerator(
        modelInfo,
        module as any,
        {
          key: 'TestComposition',
          schema: { oneOf: [{ type: 'string' }, { type: 'number' }] },
        },
        outputDir,
      );

      const result = (generator as any).process();
      expect(module.add).toHaveBeenCalledWith({
        kind: StructureKind.TypeAlias,
        name: 'TestModel',
        type: '(string | number)',
        isExported: true,
      });
      expect(result).toBeDefined();
    });

    it('should process allOf schema', () => {
      const module = fakeModule();
      const generator = new TypeGenerator(
        modelInfo,
        module as any,
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
      expect(module.add).toHaveBeenCalledWith({
        kind: StructureKind.TypeAlias,
        name: 'TestModel',
        type: 'globalThis.Exclude<(BaseModel & {\n  extra: boolean; \n}), string | number | boolean | readonly unknown[]> & ({ readonly [globalThis.Symbol.iterator]?: never } | null)',
        isExported: true,
      });
      expect(result).toBeDefined();
    });

    it('should process a string-keyed map as an interface with an index signature', () => {
      const module = fakeModule();
      const generator = new TypeGenerator(
        modelInfo,
        module as any,
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
      expect(module.add).toHaveBeenCalledWith({
        kind: StructureKind.Interface,
        name: 'TestModel',
        isExported: true,
        properties: [
          { name: '[key: string]', type: 'string', docs: undefined },
        ],
      });
      expect(result).toBeDefined();
    });

    it('should process type alias for primitive types', () => {
      const module = fakeModule();
      const generator = new TypeGenerator(
        modelInfo,
        module as any,
        {
          key: 'TestPrimitive',
          schema: { type: 'boolean' },
        },
        outputDir,
      );

      const result = (generator as any).process();
      expect(module.add).toHaveBeenCalledWith({
        kind: StructureKind.TypeAlias,
        name: 'TestModel',
        type: 'boolean',
        isExported: true,
      });
      expect(result).toBeDefined();
    });
  });

  describe('generate', () => {
    it('should call process and add JSDoc to the declaration it adds', () => {
      const module = fakeModule();
      const generator = new TypeGenerator(
        modelInfo,
        module as any,
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
      expect(module.add).toHaveBeenCalledTimes(1);
      expect(addMainSchemaJSDoc).toHaveBeenCalledWith(
        module.add.mock.results[0].value,
        {
          type: 'object',
          properties: { id: { type: 'string' } },
        },
        'TestModel',
        false,
      );
    });
  });

  describe('addPropertyToInterface', () => {
    it('should update existing property type when property already exists', () => {
      const existing = {
        name: 'existingProp',
        type: 'number',
        hasQuestionToken: true,
      };
      const declaration = {
        kind: StructureKind.Interface,
        name: 'TestModel',
        properties: [existing],
      };
      const generator = new TypeGenerator(
        modelInfo,
        {} as any,
        {} as any,
        outputDir,
      );

      (generator as any).addPropertyToInterface(declaration, 'existingProp', {
        type: 'string',
      });

      expect(declaration.properties).toEqual([
        { name: 'existingProp', type: 'string', hasQuestionToken: false },
      ]);
    });
  });

  describe('processInterface', () => {
    it('should add index signature when additionalProperties is true', () => {
      const module = fakeModule();
      const generator = new TypeGenerator(
        modelInfo,
        module as any,
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

      expect(result.properties).toEqual([
        { name: 'id', type: 'string', isReadonly: false },
        {
          name: '[key: string]',
          type: 'any',
          docs: ['Additional properties'],
        },
      ]);
      expect(result).toBe(module.add.mock.results[0].value);
    });

    it('should add a strict index signature when declared properties are required', () => {
      const module = fakeModule();
      const generator = new TypeGenerator(
        modelInfo,
        module as any,
        {
          key: 'TestModel',
          schema: {
            type: 'object',
            properties: { id: { type: 'number' } },
            required: ['id'],
            additionalProperties: { type: 'number' },
          },
        },
        outputDir,
      );

      const result = (generator as any).processInterface({
        type: 'object',
        properties: { id: { type: 'number' } },
        required: ['id'],
        additionalProperties: { type: 'number' },
      });

      expect(result.properties).toEqual([
        { name: 'id', type: 'number', isReadonly: false },
        {
          name: '[key: string]',
          type: 'number',
          docs: ['Additional properties'],
        },
      ]);
    });

    it('should take the intersection form when a required property clashes with the index signature', () => {
      // An interface may only carry a named property assignable to its index
      // signature (TS2411), which a `string` beside a `number` index is not
      // however the document declares it required.
      const module = fakeModule();
      const schema = {
        type: 'object' as const,
        properties: { id: { type: 'string' as const } },
        required: ['id'],
        additionalProperties: { type: 'number' as const },
      };
      const generator = new TypeGenerator(
        modelInfo,
        module as any,
        { key: 'TestModel', schema },
        outputDir,
      );

      const result = (generator as any).processInterface(schema);

      expect(module.add).toHaveBeenCalledTimes(1);
      expect(module.add).toHaveBeenCalledWith(
        expect.objectContaining({
          kind: StructureKind.TypeAlias,
          name: modelInfo.name,
          type: expect.stringContaining('globalThis.Record<string, number>'),
          isExported: true,
        }),
      );
      expect(result).toBe(module.add.mock.results[0].value);
    });
  });
});
