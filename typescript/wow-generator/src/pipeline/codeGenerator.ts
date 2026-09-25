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

import { IndentationText, Project, QuoteKind } from 'ts-morph';
import { analyze } from '../analysis/analyze';
import { errorMessage, GeneratorError } from '../api/errors';
import type { Logger } from '../api/logger';
import { ConsoleLogger } from '../api/logger';
import type { GenerationResult, GeneratorOptions } from '../api/options';
import { ModuleSet } from '../emit/moduleBuilder';
import { emitGeneration } from '../emitters/emit';
import { emitIndexFiles } from '../emitters/indexFiles';
import { documentTypeContext } from '../emitters/models';
import { finalizeSourceFiles } from '../finalize/finalize';
import { resolveConfiguration } from '../input/configuration';
import { parseOpenAPI } from '../input/parsers';
import { openApiDocument } from '../openapi/document';
import { findDanglingReferences } from '../openapi/references';
import { OutputStore } from '../output/outputStore';
import { resolveWowModel } from '../wow/resolveWowModel';
import type { SeamOptions } from './seams';
import { PROJECT_SEAM, SIGNAL_SEAM } from './seams';

/**
 * Main code generator class that orchestrates the generation of TypeScript code from OpenAPI specifications.
 * This class handles the entire code generation process, including parsing OpenAPI specs,
 * resolving aggregates, generating models and clients, and formatting the output.
 *
 * @example
 * ```typescript
 * const generator = new CodeGenerator({
 *   inputPath: './openapi.yaml',
 *   outputDir: './generated',
 *   tsConfigFilePath: './tsconfig.json',
 *   logger: new ConsoleLogger({ level: 'verbose' }),
 * });
 * const { files } = await generator.generate();
 * ```
 */
export class CodeGenerator {
  private readonly project: Project;
  private readonly logger: Logger;
  private readonly signal?: AbortSignal;
  /** The output of the last run, whose files the next run starts without. */
  private output?: OutputStore;

  /**
   * Creates a new CodeGenerator instance with the specified options.
   *
   * @param options - Input, output, configuration and logging of the run.
   * @throws GeneratorError of kind `configuration` if the TypeScript
   * configuration cannot be read.
   */
  constructor(private readonly options: GeneratorOptions) {
    this.logger = options.logger ?? new ConsoleLogger();
    this.signal = (options as SeamOptions)[SIGNAL_SEAM];
    this.project =
      (options as SeamOptions)[PROJECT_SEAM] ??
      createProject(options.tsConfigFilePath);
    this.project.manipulationSettings.set({
      indentationText: IndentationText.TwoSpaces,
      quoteKind: QuoteKind.Single,
      useTrailingCommas: true,
    });
    this.logger.debug(
      `Project instance created with tsConfigFilePath: ${this.options.tsConfigFilePath}`,
    );
  }

  /**
   * Generates TypeScript code from the OpenAPI specification:
   *
   * 1. reads the generator configuration, then the document, which it checks
   *    for dangling references;
   * 2. reads the document's Wow model: bounded contexts and aggregates;
   * 3. decides what the document generates (`analysis/`);
   * 4. writes every module once (`emitters/`), then the index files;
   * 5. formats, organises and types the imports, and checks the output
   *    compiles (`finalize/`);
   * 6. writes the files, removes the stale ones of the last run and records
   *    the manifest (`output/`).
   *
   * Every warning is logged as it arises, and counted.
   *
   * @returns The files written, the configuration read and how many warnings
   * the run logged.
   * @throws GeneratorError when the document or the configuration cannot be
   * read or understood, the document describes code that cannot compile, or
   * the output cannot be written. A configuration is only optional at
   * `DEFAULT_CONFIG_PATH`; one the caller named has to exist.
   *
   * @example
   * ```typescript
   * await generator.generate();
   * ```
   */
  async generate(): Promise<GenerationResult> {
    const { logger, signal, options } = this;
    let warnings = 0;
    const warn = (lines: readonly string[]) => {
      for (const line of lines) {
        warnings++;
        logger.warn(line);
      }
    };
    logger.debug('Starting code generation from OpenAPI specification');
    logger.debug(`Work directory: ${process.cwd()}`);
    logger.debug(`Input path: ${options.inputPath}`);
    logger.debug(`Output directory: ${options.outputDir}`);

    const loadOptions = {
      headers: options.headers,
      timeoutMs: options.timeoutMs,
      signal,
    };
    // The configuration first: a mistake in it fails before a remote
    // document is fetched and read.
    const configuration = await resolveConfiguration(
      options.configPath,
      logger,
      loadOptions,
    );
    warn(configuration.warnings);
    signal?.throwIfAborted();

    logger.debug('Parsing OpenAPI specification');
    const openAPI = await parseOpenAPI(options.inputPath, loadOptions);
    signal?.throwIfAborted();
    logger.debug('OpenAPI specification parsed successfully');
    const dangling = findDanglingReferences(openAPI);
    if (dangling.length > 0) {
      throw new GeneratorError(
        'specification',
        `${options.inputPath} has ${dangling.length} $ref(s) that point at nothing: ${dangling
          .slice(0, 10)
          .map(({ ref, location }) => `${ref} (at ${location})`)
          .join(', ')}${dangling.length > 10 ? ', …' : ''}`,
      );
    }

    const document = openApiDocument(openAPI);
    logger.debug('Resolving bounded context aggregates');
    const wow = resolveWowModel(document);
    warn(wow.warnings);
    logger.debug(`Resolved ${wow.contexts.size} bounded context aggregates`);

    const output = OutputStore.open(
      this.project,
      options.outputDir,
      this.output,
    );
    this.output = output;

    logger.debug('Analysing the document');
    const analysis = analyze(document, wow, configuration.config);
    warn(analysis.warnings);

    logger.debug('Writing generated modules');
    const modules = new ModuleSet(filePath => output.claim(filePath));
    emitGeneration(analysis.model, {
      modules,
      outputDir: options.outputDir,
      types: documentTypeContext(document.components),
      schemaDocs: options.schemaDocs ?? 'summary',
    });
    modules.build();
    logger.debug('Generated modules written');
    output.forgetStale();
    const outputDirectory = this.project.getDirectory(options.outputDir);
    if (outputDirectory) {
      logger.debug('Generating index files');
      warn(
        emitIndexFiles(outputDirectory, directory =>
          output.claim('index.ts', directory),
        ),
      );
      logger.debug('Optimizing source files');
      const sourceFiles = outputDirectory
        .getDescendantSourceFiles()
        .filter(file => output.files.has(file.getFilePath()));
      finalizeSourceFiles(sourceFiles, logger);
      logger.debug('Source files optimized successfully');
    } else {
      logger.debug('Output directory not found.');
    }

    logger.debug('Saving project to disk');
    await output.commit(signal);
    logger.debug('Code generation completed successfully');
    return {
      files: [...output.files].sort(),
      configPath: configuration.origin,
      warnings,
    };
  }
}

/**
 * The project a run writes into. Only the compiler options of the tsconfig
 * matter: the files it includes would be read, parsed and type-checked on
 * every run for nothing, and their global declarations could sway the
 * output.
 *
 * @throws GeneratorError of kind `configuration` when the tsconfig cannot be
 * read
 */
function createProject(tsConfigFilePath: string | undefined): Project {
  try {
    return new Project({ tsConfigFilePath, skipAddingFilesFromTsConfig: true });
  } catch (error) {
    throw new GeneratorError(
      'configuration',
      `Cannot read the TypeScript configuration ${tsConfigFilePath}: ${errorMessage(error)}`,
      { cause: error },
    );
  }
}
