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

import type { Components, Reference } from '@ahoo-wang/fetcher-openapi';
import type { ModelInfo } from '../naming/modelInfo';

/** An import a resolved type needs: a name, and the alias it goes by. */
export interface ImportRequest {
  readonly moduleSpecifier: string;
  readonly name: string;
  readonly alias?: string;
}

/** A type expression, and the imports it needs. */
export interface ResolvedType {
  readonly text: string;
  readonly imports: readonly ImportRequest[];
}

/** The imports a module already holds, as the resolver reads them. */
export interface ImportLookup {
  entries(): readonly ImportRequest[];
}

/**
 * What stays the same for every type of one document: its components, how a
 * component names its model, and the model names declared at each path.
 */
export interface TypeContext {
  readonly components?: Components;
  /** The model a reference names. */
  modelOf(reference: Reference): ModelInfo;
  /** The models declared at a path, with their `EnumText` companions. */
  namesAt(path: string): readonly string[];
}

/**
 * How a document's component keys name models: `resolveModelInfo` and
 * `resolveReferenceModelInfo` of `analysis/modelInfo.ts`.
 */
export interface ModelNaming {
  ofKey(key: string): ModelInfo;
  ofReference(reference: Reference, components?: Components): ModelInfo;
}

/**
 * Builds the context of a document. The model names of each path are read
 * once, on first use, rather than from every component for every reference.
 */
export function createTypeContext(
  components: Components | undefined,
  naming: ModelNaming,
): TypeContext {
  let namesByPath: Map<string, string[]> | undefined;
  return {
    components,
    modelOf: reference => naming.ofReference(reference, components),
    namesAt(path) {
      if (!namesByPath) {
        namesByPath = new Map();
        for (const key of Object.keys(components?.schemas ?? {})) {
          const model = naming.ofKey(key);
          let names = namesByPath.get(model.path);
          if (!names) {
            names = [];
            namesByPath.set(model.path, names);
          }
          names.push(model.name, `${model.name}EnumText`);
        }
      }
      return namesByPath.get(path) ?? [];
    },
  };
}

/** Where a type is written. */
export interface TypeScope {
  readonly context: TypeContext;
  /**
   * The declaration the type belongs to. An import never takes its name.
   * With a `path`, it is a model: the models of that path are declared beside
   * it, so a reference to one needs no import, and an import takes none of
   * their names. Without, every referenced model is imported.
   */
  readonly owner: { readonly name: string; readonly path?: string };
  /** The specifier the module imports a model by. */
  specifierOf(model: ModelInfo): string;
  /** The imports the module holds. */
  readonly imports: ImportLookup;
}

/**
 * Global names generated code relies on. A model imported under one of these
 * names would shadow the global, so the import is aliased instead: a model
 * named `Response` must not turn `Promise<Response>` into a promise of the
 * model.
 */
export const GLOBAL_TYPE_NAMES: readonly string[] = [
  'Array',
  'Blob',
  'Exclude',
  'FormData',
  'Partial',
  'Promise',
  'ReadonlyArray',
  'Record',
  'Response',
  'Symbol',
  'URLSearchParams',
];
