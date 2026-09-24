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

import type { Directory, SourceFile } from 'ts-morph';
import { Project } from 'ts-morph';
import { AggregateResolver } from './aggregate';
import { ClientGenerator } from './client';
import { GenerateContext } from './generateContext';
import { ModelGenerator } from './model';
import type { GenerationResult, GeneratorOptions, Logger } from './types';
import {
  applyTypeOnlyImports,
  beginGeneration,
  ConsoleLogger,
  forgetStaleGeneratedFiles,
  getGeneratedFilePaths,
  getOrCreateSourceFile,
  parseOpenAPI,
  resolveConfiguration,
  saveGeneration,
  WarningCounter,
} from './utils';

export { DEFAULT_CONFIG_PATH } from './utils/configuration';
export type {
  ApiClientConfiguration,
  GenerationResult,
  GeneratorConfiguration,
  GeneratorOptions,
  Logger,
} from './types';
export type { ConsoleLoggerOptions, LogLevel } from './utils/logger';
export { ConsoleLogger, SilentLogger } from './utils/logger';
export type { GeneratorErrorKind } from './errors';
export { EXIT_CODES, GeneratorError } from './errors';

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
  private readonly logger: WarningCounter;

  /**
   * Creates a new CodeGenerator instance with the specified options.
   *
   * @param options - Input, output, configuration and logging of the run.
   * @param project - Test seam: the ts-morph project to generate into. It is
   * not part of the public API, because it follows ts-morph's major version.
   * @throws Error if the TypeScript configuration cannot be read.
   */
  constructor(
    private readonly options: GeneratorOptions,
    /** @internal */
    project?: Project,
  ) {
    this.logger = new WarningCounter(options.logger ?? new ConsoleLogger());
    this.project =
      project ?? new Project({ tsConfigFilePath: options.tsConfigFilePath });
    this.logger.info(
      `Project instance created with tsConfigFilePath: ${this.options.tsConfigFilePath}`,
    );
  }

  /**
   * Generates TypeScript code from the OpenAPI specification.
   * This method performs the following steps:
   * 1. Parses the OpenAPI specification from the input path.
   * 2. Resolves bounded context aggregates.
   * 3. Loads and validates the generator configuration.
   * 4. Generates models and clients.
   * 5. Creates index files for the output directory.
   * 6. Optimizes and formats the generated source files.
   * 7. Saves the project to disk.
   *
   * @returns The files written, the configuration read and how many warnings
   * the run logged.
   * @throws GeneratorError when the document or the configuration cannot be
   * read or understood, or the document describes code that cannot compile;
   * Error when writing the output fails. A configuration is only optional at
   * {@link DEFAULT_CONFIG_PATH}; one the caller named has to exist.
   *
   * @example
   * ```typescript
   * await generator.generate();
   * ```
   */
  async generate(): Promise<GenerationResult> {
    const logger: Logger = this.logger;
    const warningsBefore = this.logger.warnings;
    logger.info('Starting code generation from OpenAPI specification');
    logger.info(`Work directory: ${process.cwd()}`);
    logger.info(`Input path: ${this.options.inputPath}`);
    logger.info(`Output directory: ${this.options.outputDir}`);

    const loadOptions = {
      headers: this.options.headers,
      timeoutMs: this.options.timeoutMs,
    };
    logger.info('Parsing OpenAPI specification');
    const openAPI = await parseOpenAPI(this.options.inputPath, loadOptions);
    logger.info('OpenAPI specification parsed successfully');

    logger.info('Resolving bounded context aggregates');
    const aggregateResolver = new AggregateResolver(openAPI);
    const boundedContextAggregates = aggregateResolver.resolve();
    logger.info(
      `Resolved ${boundedContextAggregates.size} bounded context aggregates`,
    );
    const { config, origin: configPath } = await resolveConfiguration(
      this.options.configPath,
      logger,
      loadOptions,
    );

    beginGeneration(this.project, this.options.outputDir);

    const context: GenerateContext = new GenerateContext({
      openAPI: openAPI,
      project: this.project,
      outputDir: this.options.outputDir,
      contextAggregates: boundedContextAggregates,
      logger,
      config: config,
    });

    logger.info('Generating models');
    const modelGenerator = new ModelGenerator(context);
    modelGenerator.generate();
    logger.info('Models generated successfully');

    logger.info('Generating clients');
    const clientGenerator = new ClientGenerator(context);
    clientGenerator.generate();
    logger.info('Clients generated successfully');
    forgetStaleGeneratedFiles(this.project, this.options.outputDir);
    const outputDir = this.project.getDirectory(this.options.outputDir);
    if (outputDir) {
      logger.info('Generating index files');
      this.generateIndex(outputDir);
      logger.info('Index files generated successfully');

      logger.info('Optimizing source files');
      this.optimizeSourceFiles(outputDir);
      logger.info('Source files optimized successfully');
    } else {
      logger.info('Output directory not found.');
    }

    logger.info('Saving project to disk');
    await saveGeneration(this.project, this.options.outputDir);
    logger.info('Code generation completed successfully');
    return {
      files: [...(getGeneratedFilePaths(this.project) ?? [])].sort(),
      configPath,
      warnings: this.logger.warnings - warningsBefore,
    };
  }

  /**
   * Generates index.ts files for all subdirectories in the output directory.
   * This method recursively processes all directories under the output directory,
   * creating index.ts files that export all TypeScript files and subdirectories.
   *
   * @param outputDir - The root output directory to generate index files for.
   */
  private generateIndex(outputDir: Directory) {
    this.logger.info(
      `Generating index files for output directory: ${this.options.outputDir}`,
    );
    this.processDirectory(outputDir);
    this.logger.info('Index file generation completed');
  }

  /**
   * Recursively processes all subdirectories to generate index files.
   * @param dir - The directory to process.
   */
  private processDirectory(dir: Directory): boolean {
    const subDirs = dir
      .getDirectories()
      .filter(subDir => this.processDirectory(subDir));
    this.logger.info(`Processing ${subDirs.length} subdirectories`);
    return this.generateIndexForDirectory(dir, subDirs);
  }

  /**
   * Generates an index.ts file for a specific directory.
   * Collects all .ts files (excluding index.ts) and subdirectories,
   * then creates export statements for each.
   *
   * @param dir - The directory to generate the index file for.
   */
  private generateIndexForDirectory(
    dir: Directory,
    subDirs = dir.getDirectories(),
  ): boolean {
    const dirPath = dir.getPath();
    this.logger.info(`Generating index for directory: ${dirPath}`);

    const tsFiles = dir
      .getSourceFiles()
      .filter(
        (file: SourceFile) =>
          file.getBaseName().endsWith('.ts') &&
          file.getBaseName() !== 'index.ts',
      );

    this.logger.info(
      `Found ${tsFiles.length} TypeScript files and ${subDirs.length} subdirectories in ${dirPath}`,
    );

    if (tsFiles.length === 0 && subDirs.length === 0) {
      this.logger.info(
        `No files or subdirectories to export in ${dirPath}, skipping index generation`,
      );
      return this.project.getSourceFile(`${dirPath}/index.ts`) !== undefined;
    }

    const indexFile = getOrCreateSourceFile(this.project, dirPath, 'index.ts');

    indexFile.removeText();

    for (const tsFile of tsFiles) {
      const relativePath = `./${tsFile.getBaseNameWithoutExtension()}`;
      indexFile.addExportDeclaration({
        moduleSpecifier: relativePath,
        isTypeOnly: false,
        namedExports: [],
      });
    }

    for (const subDir of subDirs) {
      const relativePath = `./${subDir.getBaseName()}`;
      indexFile.addExportDeclaration({
        moduleSpecifier: relativePath,
        isTypeOnly: false,
        namedExports: [],
      });
    }

    this.logger.info(
      `Index file generated for ${dirPath} with ${tsFiles.length + subDirs.length} exports`,
    );
    return true;
  }

  /**
   * Optimizes all source files in the output directory.
   * Performs formatting, import organization, and fixes missing imports.
   *
   * @param outputDir - The root output directory containing source files to optimize.
   */
  private optimizeSourceFiles(outputDir: Directory) {
    const written = getGeneratedFilePaths(this.project);
    const sourceFiles = outputDir
      .getDescendantSourceFiles()
      .filter(file => !written || written.has(file.getFilePath()));
    this.logger.info(
      `Optimizing ${sourceFiles.length} source files in ${outputDir.getPath()}`,
    );
    sourceFiles.forEach((sourceFile, index) => {
      this.logger.info(
        `Optimizing file [${sourceFile.getFilePath()}] - ${index + 1}/${sourceFiles.length}`,
      );
      sourceFile.formatText();
      sourceFile.organizeImports();
      sourceFile.fixMissingImports();
    });
    applyTypeOnlyImports(sourceFiles);
    this.logger.info('All source files optimized');
  }
}
