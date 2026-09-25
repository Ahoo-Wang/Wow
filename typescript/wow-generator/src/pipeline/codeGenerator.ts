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
import { IndentationText, Node, Project, QuoteKind } from 'ts-morph';
import { GeneratorError } from '../api/errors';
import type { Logger } from '../api/logger';
import { ConsoleLogger } from '../api/logger';
import type { GenerationResult, GeneratorOptions } from '../api/options';
import { ClientGenerator } from '../client';
import { finalizeSourceFiles } from '../finalize/finalize';
import { GenerateContext } from '../generateContext';
import { resolveConfiguration } from '../input/configuration';
import { parseOpenAPI } from '../input/parsers';
import { ModelGenerator } from '../model';
import { compareNames } from '../naming/order';
import { openApiDocument } from '../openapi/document';
import { findDanglingReferences } from '../openapi/references';
import {
  beginGeneration,
  forgetStaleGeneratedFiles,
  getGeneratedFilePaths,
  getOrCreateSourceFile,
  saveGeneration,
} from '../output/generatedFiles';
import type { SeamOptions } from './seams';
import { PROJECT_SEAM } from './seams';
import { WarningCounter } from './warningCounter';
import { resolveWowModel } from '../wow/resolveWowModel';

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
   * @throws Error if the TypeScript configuration cannot be read.
   */
  constructor(private readonly options: GeneratorOptions) {
    this.logger = new WarningCounter(options.logger ?? new ConsoleLogger());
    // Only the compiler options of the tsconfig matter: the files it
    // includes would be read, parsed and type-checked on every run for
    // nothing, and their global declarations could sway the output.
    this.project =
      (options as SeamOptions)[PROJECT_SEAM] ??
      new Project({
        tsConfigFilePath: options.tsConfigFilePath,
        skipAddingFilesFromTsConfig: true,
      });
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
   * Generates TypeScript code from the OpenAPI specification.
   * This method performs the following steps:
   * 1. Parses the OpenAPI specification from the input path.
   * 2. Reads its Wow model: bounded contexts and aggregates.
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
   * `DEFAULT_CONFIG_PATH`; one the caller named has to exist.
   *
   * @example
   * ```typescript
   * await generator.generate();
   * ```
   */
  async generate(): Promise<GenerationResult> {
    const logger: Logger = this.logger;
    const warningsBefore = this.logger.warnings;
    logger.debug('Starting code generation from OpenAPI specification');
    logger.debug(`Work directory: ${process.cwd()}`);
    logger.debug(`Input path: ${this.options.inputPath}`);
    logger.debug(`Output directory: ${this.options.outputDir}`);

    const loadOptions = {
      headers: this.options.headers,
      timeoutMs: this.options.timeoutMs,
    };
    logger.debug('Parsing OpenAPI specification');
    const openAPI = await parseOpenAPI(this.options.inputPath, loadOptions);
    logger.debug('OpenAPI specification parsed successfully');
    const dangling = findDanglingReferences(openAPI);
    if (dangling.length > 0) {
      throw new GeneratorError(
        'specification',
        `${this.options.inputPath} has ${dangling.length} $ref(s) that point at nothing: ${dangling
          .slice(0, 10)
          .map(({ ref, location }) => `${ref} (at ${location})`)
          .join(', ')}${dangling.length > 10 ? ', …' : ''}`,
      );
    }

    const document = openApiDocument(openAPI);
    logger.debug('Resolving bounded context aggregates');
    const wow = resolveWowModel(document);
    wow.warnings.forEach(warning => logger.warn(warning));
    logger.debug(`Resolved ${wow.contexts.size} bounded context aggregates`);
    const { config, origin: configPath } = await resolveConfiguration(
      this.options.configPath,
      logger,
      loadOptions,
    );

    beginGeneration(this.project, this.options.outputDir);

    const context: GenerateContext = new GenerateContext({
      openAPI: openAPI,
      document,
      project: this.project,
      outputDir: this.options.outputDir,
      contextAggregates: wow.contexts,
      aggregateTags: wow.aggregateTags,
      schemaDocOverrides: wow.schemaDocOverrides,
      logger,
      config: config,
      schemaDocs: this.options.schemaDocs,
    });

    logger.debug('Generating models');
    const modelGenerator = new ModelGenerator(context);
    modelGenerator.generate();
    logger.debug('Models generated successfully');

    logger.debug('Generating clients');
    const clientGenerator = new ClientGenerator(context);
    clientGenerator.generate();
    logger.debug('Clients generated successfully');

    logger.debug('Writing generated modules');
    context.modules.build();
    logger.debug('Generated modules written');
    forgetStaleGeneratedFiles(this.project, this.options.outputDir);
    const outputDir = this.project.getDirectory(this.options.outputDir);
    if (outputDir) {
      logger.debug('Generating index files');
      this.generateIndex(outputDir);
      logger.debug('Index files generated successfully');

      logger.debug('Optimizing source files');
      this.optimizeSourceFiles(outputDir);
      logger.debug('Source files optimized successfully');
    } else {
      logger.debug('Output directory not found.');
    }

    logger.debug('Saving project to disk');
    await saveGeneration(this.project, this.options.outputDir);
    logger.debug('Code generation completed successfully');
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
    this.logger.debug(
      `Generating index files for output directory: ${this.options.outputDir}`,
    );
    this.processDirectory(outputDir);
    this.logger.debug('Index file generation completed');
  }

  /**
   * Recursively writes the index files of a directory and its subdirectories.
   *
   * @param dir - The directory to process.
   * @returns The names the directory's index exports, each mapped to whether
   * it is a type only; undefined when the directory has no index.
   */
  private processDirectory(dir: Directory): ExportedNames | undefined {
    const children: IndexChild[] = [];
    for (const file of dir.getSourceFiles()) {
      const baseName = file.getBaseName();
      if (!baseName.endsWith('.ts') || baseName === 'index.ts') continue;
      children.push({
        specifier: `./${file.getBaseNameWithoutExtension()}.js`,
        exports: exportedNames(file),
      });
    }
    for (const subDir of dir.getDirectories()) {
      const exports = this.processDirectory(subDir);
      if (exports) {
        children.push({
          specifier: `./${subDir.getBaseName()}/index.js`,
          exports,
        });
      }
    }
    const dirPath = dir.getPath();
    if (children.length === 0) {
      this.logger.debug(
        `No files or subdirectories to export in ${dirPath}, skipping index generation`,
      );
      return this.project.getSourceFile(`${dirPath}/index.ts`)
        ? new Map()
        : undefined;
    }
    return this.writeIndex(dirPath, children);
  }

  /**
   * Writes one index file.
   *
   * `export *` from two modules that export the same name is an error
   * (TS2308); it happens when two packages hold a model of the same name. A
   * child exporting such a name is re-exported by name instead, leaving the
   * ambiguous names out: they stay importable from their own modules.
   *
   * @param dirPath - The directory the index is written to
   * @param children - Its files and indexed subdirectories
   * @returns The names the index exports
   */
  private writeIndex(dirPath: string, children: IndexChild[]): ExportedNames {
    const owners = new Map<string, string[]>();
    for (const child of children) {
      for (const name of child.exports.keys()) {
        owners.set(name, [...(owners.get(name) ?? []), child.specifier]);
      }
    }
    const ambiguous = [...owners]
      .filter(([, specifiers]) => specifiers.length > 1)
      .map(([name]) => name)
      .sort();
    for (const name of ambiguous) {
      this.logger.warn(
        `${name} is exported by both ${owners.get(name)!.join(' and ')} in ${dirPath}; its index leaves it out, so import it from its own module.`,
      );
    }
    const indexFile = getOrCreateSourceFile(this.project, dirPath, 'index.ts');
    indexFile.removeText();
    const exported: ExportedNames = new Map();
    for (const child of children) {
      const names = [...child.exports]
        .filter(([name]) => !ambiguous.includes(name))
        .sort(([left], [right]) => compareNames(left, right));
      names.forEach(([name, typeOnly]) => exported.set(name, typeOnly));
      if (names.length === child.exports.size) {
        indexFile.addExportDeclaration({ moduleSpecifier: child.specifier });
      } else if (names.length > 0) {
        indexFile.addExportDeclaration({
          moduleSpecifier: child.specifier,
          namedExports: names.map(([name, isTypeOnly]) => ({
            name,
            isTypeOnly,
          })),
        });
      }
    }
    if (indexFile.getStatements().length === 0) {
      // Every name was ambiguous; the index stays a module its parent re-exports.
      indexFile.addStatements('export {};');
    }
    this.logger.debug(
      `Index file generated for ${dirPath} with ${children.length} exports`,
    );
    return exported;
  }

  /**
   * Finishes the files this generation wrote under the output directory; see
   * {@link finalizeSourceFiles}.
   *
   * @param outputDir - The root output directory containing source files to optimize.
   */
  private optimizeSourceFiles(outputDir: Directory) {
    const written = getGeneratedFilePaths(this.project);
    const sourceFiles = outputDir
      .getDescendantSourceFiles()
      .filter(file => !written || written.has(file.getFilePath()));
    this.logger.debug(
      `Optimizing ${sourceFiles.length} source files in ${outputDir.getPath()}`,
    );
    finalizeSourceFiles(sourceFiles, this.logger);
    this.logger.debug('All source files optimized');
  }
}

/** A name a module exports, mapped to whether it is only a type. */
type ExportedNames = Map<string, boolean>;

/** A module an index re-exports. */
interface IndexChild {
  readonly specifier: string;
  readonly exports: ExportedNames;
}

/**
 * The names a generated file exports. A name is type-only when every one of
 * its declarations is an interface or a type alias; an enum or a class is a
 * value as well.
 */
function exportedNames(file: SourceFile): ExportedNames {
  const names: ExportedNames = new Map();
  for (const [name, declarations] of file.getExportedDeclarations()) {
    names.set(
      name,
      declarations.every(
        declaration =>
          Node.isInterfaceDeclaration(declaration) ||
          Node.isTypeAliasDeclaration(declaration),
      ),
    );
  }
  return names;
}
