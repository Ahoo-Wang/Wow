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

import { runInNewContext } from 'node:vm';
import type { Components, Reference, Schema } from '@ahoo-wang/fetcher-openapi';
import type { SourceFile } from 'ts-morph';
import { Project, ts } from 'ts-morph';
import { afterEach } from 'vitest';
import { ModuleBuilder } from '../../src/emit/moduleBuilder';
import { TypeGenerator } from '../../src/model/typeGenerator';

/**
 * The project the models of a test file are written into and type-checked
 * in, created on first use.
 *
 * A new project parses TypeScript's library - lib.dom alone is 40,000 lines -
 * before it can check a line; with coverage on, that took nearly all of a
 * test's time. One project per test file parses it once. Each call writes its
 * own directory, and the files of a test leave the project when it ends.
 */
let project: Project | undefined;
let directories = 0;
const written: SourceFile[] = [];

afterEach(() => {
  for (const file of written.splice(0)) project?.removeSourceFile(file);
});

function modelProject(): Project {
  project ??= new Project({
    useInMemoryFileSystem: true,
    compilerOptions: {
      strict: true,
      skipLibCheck: true,
      lib: ['lib.es2020.d.ts', 'lib.dom.d.ts', 'lib.dom.iterable.d.ts'],
    },
  });
  return project;
}

/** Models written into one file, and what type-checking it reported. */
export interface GeneratedModels {
  /** The file: the models, then the statements the test added. */
  readonly file: SourceFile;
  /** The message of each diagnostic of the file under `strict`. */
  readonly diagnostics: unknown[];
}

/**
 * Writes models into a `types.ts` of their own, as the generator writes the
 * models of one package, adds `statements` after them and type-checks the
 * file.
 *
 * @param models - The schema of each model, by name, written in this order
 * @param statements - Code using the models, such as assignments marked
 * `@ts-expect-error`
 * @param components - What references resolve against; none by default
 */
export function generateModels(
  models: Record<string, Schema | Reference>,
  statements = '',
  components?: Components,
): GeneratedModels {
  const file = writeModels(models, components);
  if (statements) file.addStatements(statements);
  return { file, diagnostics: diagnosticsOf(file) };
}

/** The message of each diagnostic of a file under `strict`. */
export function diagnosticsOf(file: SourceFile): unknown[] {
  return file
    .getPreEmitDiagnostics()
    .map(diagnostic => diagnostic.getMessageText());
}

/**
 * {@link generateModels} for a single model named `Model`.
 */
export function generateModel(
  schema: Schema | Reference,
  statements = '',
  components?: Components,
): GeneratedModels {
  return generateModels({ Model: schema }, statements, components);
}

/**
 * Writes models into a `types.ts` of their own, without type-checking it.
 *
 * @param models - The schema of each model, by name, written in this order
 * @param components - What references resolve against; none by default
 */
export function writeModels(
  models: Record<string, Schema | Reference>,
  components?: Components,
): SourceFile {
  const directory = `/models-${++directories}`;
  const file = modelProject().createSourceFile(`${directory}/types.ts`, '');
  written.push(file);
  const module = new ModuleBuilder(file);
  for (const [name, schema] of Object.entries(models)) {
    new TypeGenerator(
      { name, path: '/' },
      module,
      { key: name, schema },
      directory,
      components,
    ).generate();
  }
  module.build();
  return file;
}

/**
 * The type a schema resolves to in a model's module, as a property or an
 * alias of the model would use it.
 *
 * @param schema - The schema
 * @param components - What references resolve against
 */
export function resolveModelType(
  schema: Schema | Reference,
  components?: Components,
): string {
  const directory = `/models-${++directories}`;
  const file = modelProject().createSourceFile(`${directory}/types.ts`, '');
  written.push(file);
  return new TypeGenerator(
    { name: 'Model', path: '/' },
    new ModuleBuilder(file),
    { key: 'Model', schema },
    directory,
    components,
  ).resolveType(schema);
}

/**
 * Runs a file of models as a CommonJS module and returns what it exports.
 *
 * @param file - A file {@link generateModels} wrote
 */
export function runModels(file: SourceFile): Record<string, unknown> {
  const { outputText } = ts.transpileModule(file.getFullText(), {
    compilerOptions: {
      module: ts.ModuleKind.CommonJS,
      target: ts.ScriptTarget.ES2020,
    },
  });
  const exports: Record<string, unknown> = {};
  runInNewContext(outputText, { exports });
  return exports;
}
