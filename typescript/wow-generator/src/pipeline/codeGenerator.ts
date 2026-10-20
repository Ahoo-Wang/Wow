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
import { loadDocument, validateOpenAPIDocument } from '../input/parsers';
import { analyzeManifest } from '../manifest/analyze';
import {
  isManifestDocument,
  type ManifestDocument,
  validateManifest,
} from '../manifest/document';
import { emitManifestGeneration } from '../emitters/manifestClients';
import { openApiDocument } from '../openapi/document';
import { findDanglingReferences } from '../openapi/references';
import { OutputStore } from '../output/outputStore';
import type { SeamOptions } from './seams';
import { PROJECT_SEAM, SIGNAL_SEAM } from './seams';

/**
 * Main code generator class that orchestrates the generation of TypeScript code
 * from a Wow Manifest or an OpenAPI document. A Manifest generates the typed
 * clients of a Wow service on `@ahoo-wang/wow-client/v10`; an OpenAPI
 * document generates its models and Fetcher decorator API clients.
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
   * Generates TypeScript code from the input document:
   *
   * 1. reads the generator configuration, then the document; a Manifest goes
   *    to `generateFromManifest`, an OpenAPI document is checked for
   *    dangling references;
   * 2. decides what the document generates (`analysis/`);
   * 3. writes every module once (`emitters/`), then the index files;
   * 4. formats, organises and types the imports, and checks the output
   *    compiles (`finalize/`);
   * 5. writes the files, removes the stale ones of the last run and records
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
    logger.debug('Starting code generation');
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

    logger.debug('Parsing the input document');
    const input = await loadDocument(options.inputPath, loadOptions);
    signal?.throwIfAborted();
    if (isManifestDocument(input)) {
      return this.generateFromManifest(
        validateManifest(input, options.inputPath),
        configuration.origin,
        warn,
        () => warnings,
      );
    }
    const openAPI = validateOpenAPIDocument(input, options.inputPath);
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

    const output = OutputStore.open(
      this.project,
      options.outputDir,
      this.output,
    );
    this.output = output;

    logger.debug('Analysing the document');
    const analysis = analyze(document, configuration.config);
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

  /**
   * Generates the clients of a Wow V10 service from its Manifest (decision
   * D74, roadmap 5a.3): the types of its schemas, and for each aggregate its
   * command, query and state clients on `@ahoo-wang/wow-client/v10`. The
   * configuration's API clients are an OpenAPI document's and do not apply.
   */
  private async generateFromManifest(
    manifest: ManifestDocument,
    configPath: string | undefined,
    warn: (lines: readonly string[]) => void,
    warnings: () => number,
  ): Promise<GenerationResult> {
    const { logger, signal, options } = this;
    const output = OutputStore.open(
      this.project,
      options.outputDir,
      this.output,
    );
    this.output = output;
    logger.debug('Analysing the Manifest');
    const analysis = analyzeManifest(manifest);
    warn(analysis.warnings);
    const modules = new ModuleSet(filePath => output.claim(filePath));
    emitManifestGeneration(analysis.model, modules, options.outputDir);
    modules.build();
    output.forgetStale();
    const outputDirectory = this.project.getDirectory(options.outputDir);
    if (outputDirectory) {
      warn(
        emitIndexFiles(outputDirectory, directory =>
          output.claim('index.ts', directory),
        ),
      );
      finalizeSourceFiles(
        outputDirectory
          .getDescendantSourceFiles()
          .filter(file => output.files.has(file.getFilePath())),
        logger,
      );
    }
    await output.commit(signal);
    logger.debug('Code generation from the Manifest completed successfully');
    return {
      files: [...output.files].sort(),
      configPath,
      warnings: warnings(),
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
