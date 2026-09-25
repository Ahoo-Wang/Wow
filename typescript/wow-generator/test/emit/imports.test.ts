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

import { Project } from 'ts-morph';
import { describe, expect, it } from 'vitest';
import {
  addImport,
  addImportBoundedContext,
  addImportModelInfo,
  addImportRefModel,
  getModelFileName,
  relativeModuleSpecifier,
} from '../../src/emit/imports';
import { ModuleBuilder } from '../../src/emit/moduleBuilder';
import type { ModelInfo } from '../../src/model/modelInfo';

// NOTE: @ahoo-wang/fetcher is NOT mocked here — the real combineURLs runs so
// that path-joining behavior (slash collapsing) is exercised, not hidden.

/** A module written to `/src/client.ts`. */
function module(): ModuleBuilder {
  const project = new Project({ useInMemoryFileSystem: true });
  return new ModuleBuilder(project.createSourceFile('/src/client.ts', ''));
}

describe('imports', () => {
  describe('getModelFileName', () => {
    it('should return the model file path', () => {
      const modelInfo: ModelInfo = {
        name: 'User',
        path: 'models',
      };

      expect(getModelFileName(modelInfo)).toBe('models/types.ts');
    });
  });

  describe('addImport', () => {
    it('imports names from a module once each, in the order asked for', () => {
      const target = module();

      addImport(target, '@/models', ['User', 'Address']);
      const imported = addImport(target, '@/models', ['User', 'Role']);

      expect(imported.map(item => item.name)).toEqual([
        'User',
        'Address',
        'Role',
      ]);
      expect(target.imports.structures()).toEqual([
        {
          moduleSpecifier: '@/models',
          namedImports: ['User', 'Address', 'Role'],
        },
      ]);
    });

    it('keeps one declaration per module, in the order first imported', () => {
      const target = module();

      addImport(target, 'b', ['B']);
      addImport(target, 'a', ['A']);
      addImport(target, 'b', ['C']);

      expect(
        target.imports.structures().map(item => item.moduleSpecifier),
      ).toEqual(['b', 'a']);
    });
  });

  describe('addImportRefModel', () => {
    it('imports a package path as it is', () => {
      const target = module();

      addImportRefModel(target, '/output', {
        name: 'WowType',
        path: '@ahoo-wang/wow-client/types.ts',
      });

      expect(target.imports.structures()).toEqual([
        {
          moduleSpecifier: '@ahoo-wang/wow-client/types.ts',
          namedImports: ['WowType'],
        },
      ]);
    });

    it('imports a model relative to the importing file', () => {
      const target = module();

      addImportRefModel(target, '/output', { name: 'User', path: 'models' });

      expect(target.imports.structures()).toEqual([
        {
          moduleSpecifier: '../output/models/types.js',
          namedImports: ['User'],
        },
      ]);
    });

    it('imports a path starting with the alias as it is', () => {
      const target = module();

      addImportRefModel(target, '/output', {
        name: 'AliasType',
        path: '@/custom/path',
      });

      expect(target.imports.structures()).toEqual([
        { moduleSpecifier: '@/custom/path', namedImports: ['AliasType'] },
      ]);
    });
  });

  describe('addImportModelInfo', () => {
    it('does not import a model declared beside the current one', () => {
      const target = module();

      const imported = addImportModelInfo(
        { name: 'User', path: 'models' },
        target,
        '/output',
        { name: 'Address', path: 'models' },
      );

      expect(imported).toBeUndefined();
      expect(target.imports.structures()).toEqual([]);
    });

    it('imports a model of another path', () => {
      const target = module();

      addImportModelInfo({ name: 'User', path: 'models' }, target, '/output', {
        name: 'Product',
        path: 'products',
      });

      expect(target.imports.structures()).toEqual([
        {
          moduleSpecifier: '../output/products/types.js',
          namedImports: ['Product'],
        },
      ]);
    });
  });

  describe('addImportBoundedContext', () => {
    it('imports the alias constant of a bounded context', () => {
      const target = module();

      addImportBoundedContext(
        target,
        '/src',
        'example',
        'EXAMPLE_BOUNDED_CONTEXT_ALIAS',
      );

      expect(target.imports.structures()).toEqual([
        {
          moduleSpecifier: './example/boundedContext.js',
          namedImports: ['EXAMPLE_BOUNDED_CONTEXT_ALIAS'],
        },
      ]);
    });
  });

  describe('relativeModuleSpecifier', () => {
    it('ends a file with .js and a directory with its index', () => {
      expect(relativeModuleSpecifier('/src', '/src/a/types.ts')).toBe(
        './a/types.js',
      );
      expect(relativeModuleSpecifier('/src/a', '/src/b')).toBe('../b/index.js');
    });
  });
});
