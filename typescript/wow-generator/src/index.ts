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
import * as fs from 'fs';
import * as path from 'path';

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
  }

  /**
   * Generates TypeScript code from the OpenAPI specification.
   * Parses the OpenAPI spec, resolves aggregates, generates models and clients,
   * and formats the output files.
   */
  async generate(): Promise<void> {
    const openAPI = await parseOpenAPI(this.options.inputPath);
    const aggregateResolver = new AggregateResolver(openAPI);
    const boundedContextAggregates = aggregateResolver.resolve();
    const context: GenerateContext = {
      openAPI: openAPI,
      project: this.project,
      outputDir: this.options.outputDir,
      contextAggregates: boundedContextAggregates,
      logger: this.options.logger,
    };
    const modelGenerator = new ModelGenerator(context);
    modelGenerator.generate();
    const clientGenerator = new ClientGenerator(context);
    clientGenerator.generate();
    this.generateIndex();
    this.optimizeSourceFiles();
    await this.project.save();
  }

  /**
   * Generates index.ts files for all subdirectories in the output directory.
   * Scans all directories, gets all .ts files in each directory,
   * and creates index.ts files with export * from './xxx' statements.
   */
  generateIndex() {
    const outputDir = this.project.getDirectory(this.options.outputDir);
    if (!outputDir) {
      return;
    }
    this.processDirectory(outputDir);
  }

  private processDirectory(dir: Directory) {
    const subDirs = dir.getDirectories();
    for (const subDir of subDirs) {
      this.generateIndexForDirectory(subDir);
      this.processDirectory(subDir);
    }
  }

  /**
   * Generates an index.ts file for a specific directory.
   * @param dir - The directory to generate index.ts for
   */
  private generateIndexForDirectory(dir: Directory) {
    const tsFiles = dir
      .getSourceFiles()
      .filter(
        (file: SourceFile) =>
          file.getBaseName().endsWith('.ts') &&
          file.getBaseName() !== 'index.ts',
      );

    // Get subdirectories using fs
    const dirPath = dir.getPath();
    let subDirNames: string[] = [];
    try {
      subDirNames = fs.readdirSync(dirPath).filter(item => {
        const itemPath = path.join(dirPath, item);
        return fs.statSync(itemPath).isDirectory();
      });
    } catch {
      // Ignore if can't read
    }

    if (tsFiles.length === 0 && subDirNames.length === 0) {
      return; // No files or subdirs to export
    }

    const indexFilePath = `${dirPath}/index.ts`;
    const indexFile =
      this.project.getSourceFile(indexFilePath) ||
      this.project.createSourceFile(indexFilePath, '', { overwrite: true });

    // Clear existing content
    indexFile.removeText();

    // Add export statements for .ts files
    for (const tsFile of tsFiles) {
      const relativePath = `./${tsFile.getBaseNameWithoutExtension()}`;
      indexFile.addExportDeclaration({
        moduleSpecifier: relativePath,
        isTypeOnly: false,
        namedExports: [],
      });
    }

    // Add export statements for subdirectories
    for (const subDirName of subDirNames) {
      const relativePath = `./${subDirName}`;
      indexFile.addExportDeclaration({
        moduleSpecifier: relativePath,
        isTypeOnly: false,
        namedExports: [],
      });
    }
  }

  optimizeSourceFiles() {
    this.project.getSourceFiles().forEach(sourceFile => {
      sourceFile.formatText();
      sourceFile.organizeImports();
      sourceFile.fixMissingImports();
    });
  }
}
