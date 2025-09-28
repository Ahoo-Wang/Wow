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
import { GenerateContext, GeneratorOptions } from './types';
import { parseOpenAPI } from './utils';
import { AggregateResolver } from './aggregate';
import { ModelGenerator } from './model';
import { ClientGenerator } from './client';

/**
 * Main code generator class that orchestrates the generation of TypeScript code from OpenAPI specifications.
 * Handles model generation, client generation, and project formatting.
 */
export class CodeGenerator {
  private readonly project: Project;

  /**
   * Creates a new CodeGenerator instance.
   * @param options - Configuration options for code generation
   */
  constructor(private readonly options: GeneratorOptions) {
    this.project = options.project;
    this.options.logger.info('CodeGenerator instance created');
  }

  /**
   * Generates TypeScript code from the OpenAPI specification.
   * Parses the OpenAPI spec, resolves aggregates, generates models and clients,
   * and formats the output files.
   */
  async generate(): Promise<void> {
    this.options.logger.info(
      'Starting code generation from OpenAPI specification',
    );
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

    const context: GenerateContext = {
      openAPI: openAPI,
      project: this.project,
      outputDir: this.options.outputDir,
      contextAggregates: boundedContextAggregates,
      logger: this.options.logger,
    };

    this.options.logger.info('Generating models');
    const modelGenerator = new ModelGenerator(context);
    modelGenerator.generate();
    this.options.logger.info('Models generated successfully');

    this.options.logger.info('Generating clients');
    const clientGenerator = new ClientGenerator(context);
    clientGenerator.generate();
    this.options.logger.info('Clients generated successfully');

    this.options.logger.info('Generating index files');
    this.generateIndex();
    this.options.logger.info('Index files generated successfully');

    this.options.logger.info('Optimizing source files');
    this.optimizeSourceFiles();
    this.options.logger.info('Source files optimized successfully');

    this.options.logger.info('Saving project to disk');
    await this.project.save();
    this.options.logger.info('Code generation completed successfully');
  }

  /**
   * Generates index.ts files for all subdirectories in the output directory.
   * Scans all directories, gets all .ts files in each directory,
   * and creates index.ts files with export * from './xxx' statements.
   */
  generateIndex() {
    this.options.logger.info(
      `Generating index files for output directory: ${this.options.outputDir}`,
    );
    const outputDir = this.project.getDirectory(this.options.outputDir);
    if (!outputDir) {
      this.options.logger.info(
        'Output directory not found, skipping index generation',
      );
      return;
    }
    this.processDirectory(outputDir);
    this.generateIndexForDirectory(outputDir);
    this.options.logger.info('Index file generation completed');
  }

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
   * @param dir - The directory to generate index.ts for
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

    // Get subdirectories using fs
    let subDirs: Directory[] = dir.getDirectories();

    this.options.logger.info(
      `Found ${tsFiles.length} TypeScript files and ${subDirs.length} subdirectories in ${dirPath}`,
    );

    if (tsFiles.length === 0 && subDirs.length === 0) {
      this.options.logger.info(
        `No files or subdirectories to export in ${dirPath}, skipping index generation`,
      );
      return; // No files or subdirs to export
    }

    const indexFilePath = `${dirPath}/index.ts`;
    this.options.logger.info(`Creating/updating index file: ${indexFilePath}`);
    const indexFile =
      this.project.getSourceFile(indexFilePath) ||
      this.project.createSourceFile(indexFilePath, '', { overwrite: true });

    // Clear existing content
    indexFile.removeText();

    // Add export statements for .ts files
    for (const tsFile of tsFiles) {
      const relativePath = `./${tsFile.getBaseNameWithoutExtension()}`;
      this.options.logger.info(`Adding export for file: ${relativePath}`);
      indexFile.addExportDeclaration({
        moduleSpecifier: relativePath,
        isTypeOnly: false,
        namedExports: [],
      });
    }

    // Add export statements for subdirectories
    for (const subDir of subDirs) {
      const relativePath = `./${subDir.getBaseName()}`;
      this.options.logger.info(
        `Adding export for subdirectory: ${relativePath}`,
      );
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

  optimizeSourceFiles() {
    const sourceFiles = this.project.getSourceFiles();
    this.options.logger.info(`Optimizing ${sourceFiles.length} source files`);
    sourceFiles.forEach((sourceFile, index) => {
      this.options.logger.info(
        `Optimizing file ${index + 1}/${sourceFiles.length}`,
      );
      sourceFile.formatText();
      sourceFile.organizeImports();
      sourceFile.fixMissingImports();
    });
    this.options.logger.info('All source files optimized');
  }
}
