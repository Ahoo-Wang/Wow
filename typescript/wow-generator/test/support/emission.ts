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

import type { OpenAPI } from '@ahoo-wang/fetcher-openapi';
import type { SourceFile } from 'ts-morph';
import { Project, QuoteKind } from 'ts-morph';
import { analyze } from '../../src/analysis/analyze';
import type { Analysis } from '../../src/analysis/model';
import type { GeneratorConfiguration } from '../../src/api/configuration';
import type { SchemaDocs } from '../../src/api/options';
import { ModuleSet } from '../../src/emit/moduleBuilder';
import { emitGeneration } from '../../src/emitters/emit';
import { documentTypeContext } from '../../src/emitters/models';
import { combinePaths } from '../../src/naming/paths';
import { openApiDocument } from '../../src/openapi/document';
import type { WowModel } from '../../src/wow/model';
import { resolveWowModel } from '../../src/wow/resolveWowModel';

/** How a test document is read. */
export interface AnalysisOptions {
  readonly config?: GeneratorConfiguration;
  /**
   * Tags to treat as aggregate tags on top of the ones the document's Wow
   * metadata names: their operations go to no API client.
   */
  readonly aggregateTags?: Iterable<string>;
}

/** A minimal OpenAPI 3 document with the given parts. */
export function openAPIDocument(parts: Partial<OpenAPI>): OpenAPI {
  return {
    openapi: '3.0.3',
    info: { title: 'Test', version: '1' },
    paths: {},
    ...parts,
  } as OpenAPI;
}

/**
 * What a document generates, as the analysis decides it, without writing
 * anything.
 */
export function analyzeDocument(
  openAPI: OpenAPI,
  options: AnalysisOptions = {},
): Analysis & { readonly wow: WowModel } {
  const document = openApiDocument(openAPI);
  let wow = resolveWowModel(document);
  if (options.aggregateTags) {
    wow = {
      ...wow,
      aggregateTags: new Set([...wow.aggregateTags, ...options.aggregateTags]),
    };
  }
  return { ...analyze(document, wow, options.config ?? {}), wow };
}

/** Where and how a test document is generated. */
export interface EmitOptions extends AnalysisOptions {
  readonly schemaDocs?: SchemaDocs;
  /** The project written into; an in-memory one by default. */
  readonly project?: Project;
  /** The output directory; `/out` by default. */
  readonly outputDir?: string;
}

/**
 * Generates a document up to the modules: no index files, no formatting,
 * nothing saved.
 *
 * @returns The project, the generation model, the warnings, and readers of
 * the files under the output directory
 */
export function emitDocument(openAPI: OpenAPI, options: EmitOptions = {}) {
  const analysis = analyzeDocument(openAPI, options);
  const project =
    options.project ??
    new Project({
      useInMemoryFileSystem: true,
      manipulationSettings: { quoteKind: QuoteKind.Single },
    });
  const outputDir = options.outputDir ?? '/out';
  const modules = new ModuleSet(filePath => {
    const fileName = combinePaths(outputDir, filePath);
    return (
      project.getSourceFile(fileName) ?? project.createSourceFile(fileName, '')
    );
  });
  emitGeneration(analysis.model, {
    modules,
    outputDir,
    types: documentTypeContext(openAPI.components),
    schemaDocs: options.schemaDocs ?? 'summary',
  });
  modules.build();
  const file = (path: string): SourceFile =>
    project.getSourceFileOrThrow(combinePaths(outputDir, path));
  return {
    project,
    model: analysis.model,
    warnings: analysis.warnings,
    file,
    method: (path: string, className: string, methodName: string) =>
      file(path).getClassOrThrow(className).getMethodOrThrow(methodName),
  };
}
