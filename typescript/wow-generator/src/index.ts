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

import { Directory, Project, SourceFile } from 'ts-morph';
import { AggregateResolver } from './aggregate';
import { ClientGenerator } from './client';
import { GenerateContext } from './generateContext';
import { ModelGenerator } from './model';
import { GeneratorConfiguration, GeneratorOptions } from './types';
import { parseConfiguration, parseOpenAPI } from './utils';

/**
 * Default path to the generator configuration file.
 * This path is used when no custom config path is provided in the generator options.
 */
export const DEFAULT_CONFIG_PATH = './fetcher-generator.config.json';

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
 *   logger: new ConsoleLogger(),
 * });
 * await generator.generate();
 * ```
 */
export class CodeGenerator {
  private readonly project: Project;

  /**
   * Creates a new CodeGenerator instance with the specified options.
   *
   * @param options - Configuration options for the code generation process, including input/output paths, TypeScript config, and logger.
   * @throws Error if the project initialization fails due to invalid TypeScript configuration or missing files.
   */
  constructor(private readonly options: GeneratorOptions) {
    this.project = new Project(options);
    this.options.logger.info(
      `Project instance created with tsConfigFilePath: ${this.options.tsConfigFilePath}`,
    );
  }

  /**
   * Generates TypeScript code from the OpenAPI specification.
   * This method performs the following steps:
   * 1. Parses the OpenAPI specification from the input path.
   * 2. Resolves bounded context aggregates.
   * 3. Parses the generator configuration.
   * 4. Generates models and clients.
   * 5. Creates index files for the output directory.
   * 6. Optimizes and formats the generated source files.
   * 7. Saves the project to disk.
   *
   * @returns A promise that resolves when code generation is complete.
   * @throws Error if OpenAPI parsing fails, configuration parsing fails, or file operations fail.
   *
   * @example
   * ```typescript
   * await generator.generate();
   * ```
   */
  async generate(): Promise<void> {
    this.options.logger.info(
      'Starting code generation from OpenAPI specification',
    );
    const currentWorkingDir = process.cwd();
    this.options.logger.info(`Work directory: ${currentWorkingDir}`);
    this.options.logger.info(`Input path: ${this.options.inputPath}`);
    this.options.logger.info(`Output directory: ${this.options.outputDir}`);

    this.options.logger.info('Parsing OpenAPI specification');
    const openAPI = await parseOpenAPI(this.options.inputPath);
    this.options.logger.info('OpenAPI specification parsed successfully');

    this.options.logger.info('Resolving bounded context aggregates');
    const aggregateResolver = new AggregateResolver(openAPI);
    const boundedContextAggregates = aggregateResolver.resolve();
    this.options.logger.info(
      `Resolved ${boundedContextAggregates.size} bounded context aggregates`,
    );
    const configPath = this.options.configPath ?? DEFAULT_CONFIG_PATH;
    let config: GeneratorConfiguration = {};
    try {
      this.options.logger.info(`Parsing configuration file: ${configPath}`);
      config = await parseConfiguration(configPath);
    } catch (e) {
      this.options.logger.info(`Configuration file parsing failed: ${e}`);
    }

    const context: GenerateContext = new GenerateContext({
      openAPI: openAPI,
      project: this.project,
      outputDir: this.options.outputDir,
      contextAggregates: boundedContextAggregates,
      logger: this.options.logger,
      config: config,
    });

    this.options.logger.info('Generating models');
    const modelGenerator = new ModelGenerator(context);
    modelGenerator.generate();
    this.options.logger.info('Models generated successfully');

    this.options.logger.info('Generating clients');
    const clientGenerator = new ClientGenerator(context);
    clientGenerator.generate();
    this.options.logger.info('Clients generated successfully');
    const outputDir = this.project.getDirectory(this.options.outputDir);
    if (!outputDir) {
      this.options.logger.info('Output directory not found.');
      return;
    }
    this.options.logger.info('Generating index files');
    this.generateIndex(outputDir);
    this.options.logger.info('Index files generated successfully');

    this.options.logger.info('Optimizing source files');
    this.optimizeSourceFiles(outputDir);
    this.options.logger.info('Source files optimized successfully');

    this.options.logger.info('Saving project to disk');
    await this.project.save();
    this.options.logger.info('Code generation completed successfully');
  }

  /**
   * Generates index.ts files for all subdirectories in the output directory.
   * This method recursively processes all directories under the output directory,
   * creating index.ts files that export all TypeScript files and subdirectories.
   *
   * @param outputDir - The root output directory to generate index files for.
   *
   * @example
   * ```typescript
   * const outputDir = project.getDirectory('./generated');
   * generator.generateIndex(outputDir);
   * ```
   */
  generateIndex(outputDir: Directory) {
    this.options.logger.info(
      `Generating index files for output directory: ${this.options.outputDir}`,
    );
    this.processDirectory(outputDir);
    this.generateIndexForDirectory(outputDir);
    this.options.logger.info('Index file generation completed');
  }

  /**
   * Recursively processes all subdirectories to generate index files.
   * @param dir - The directory to process.
   */
  private processDirectory(dir: Directory) {
    const subDirs = dir.getDirectories();
    this.options.logger.info(`Processing ${subDirs.length} subdirectories`);
    for (const subDir of subDirs) {
      this.options.logger.info(`Processing subdirectory: ${subDir.getPath()}`);
      this.generateIndexForDirectory(subDir);
      this.processDirectory(subDir);
    }
  }

  /**
   * Generates an index.ts file for a specific directory.
   * Collects all .ts files (excluding index.ts) and subdirectories,
   * then creates export statements for each.
   *
   * @param dir - The directory to generate the index file for.
   */
  private generateIndexForDirectory(dir: Directory) {
    const dirPath = dir.getPath();
    this.options.logger.info(`Generating index for directory: ${dirPath}`);

    const tsFiles = dir
      .getSourceFiles()
      .filter(
        (file: SourceFile) =>
          file.getBaseName().endsWith('.ts') &&
          file.getBaseName() !== 'index.ts',
      );

    const subDirs: Directory[] = dir.getDirectories();

    this.options.logger.info(
      `Found ${tsFiles.length} TypeScript files and ${subDirs.length} subdirectories in ${dirPath}`,
    );

    if (tsFiles.length === 0 && subDirs.length === 0) {
      this.options.logger.info(
        `No files or subdirectories to export in ${dirPath}, skipping index generation`,
      );
      return;
    }

    const indexFilePath = `${dirPath}/index.ts`;
    const indexFile =
      this.project.getSourceFile(indexFilePath) ||
      this.project.createSourceFile(indexFilePath, '', { overwrite: true });

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

    this.options.logger.info(
      `Index file generated for ${dirPath} with ${tsFiles.length + subDirs.length} exports`,
    );
  }

  /**
   * Optimizes all source files in the output directory.
   * Performs formatting, import organization, and fixes missing imports.
   *
   * @param outputDir - The root output directory containing source files to optimize.
   */
  optimizeSourceFiles(outputDir: Directory) {
    const sourceFiles = outputDir.getDescendantSourceFiles();
    this.options.logger.info(
      `Optimizing ${sourceFiles.length} source files in ${outputDir.getPath()}`,
    );
    sourceFiles.forEach((sourceFile, index) => {
      this.options.logger.info(
        `Optimizing file [${sourceFile.getFilePath()}] - ${index + 1}/${sourceFiles.length}`,
      );
      sourceFile.formatText();
      sourceFile.organizeImports();
      sourceFile.fixMissingImports();
    });
    this.options.logger.info('All source files optimized');
  }
}
