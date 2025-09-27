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

import { ConsoleLogger } from './logger';
import { GeneratorOptions } from '../types';
import { CodeGenerator } from '../index';
import { Project } from 'ts-morph';

/**
 * Validates the input path or URL.
 * @param input - Input path or URL
 * @returns true if valid
 */
export function validateInput(input: string): boolean {
  if (!input) return false;

  // Check if it's a URL
  try {
    const url = new URL(input);
    return url.protocol === 'http:' || url.protocol === 'https:';
  } catch {
    // Not a URL, check if it's a file path
    // For file paths, we'll let parseOpenAPI handle it
    return input.length > 0;
  }
}

/**
 * Action handler for the generate command.
 * @param options - Command options
 */
export async function generateAction(options: {
  input: string;
  output: string;
  config?: string;
  verbose?: boolean;
  dryRun?: boolean;
}) {
  const logger = new ConsoleLogger();

  // Handle signals
  process.on('SIGINT', () => {
    logger.error('Generation interrupted by user');
    process.exit(130);
  });

  // Validate input
  if (!validateInput(options.input)) {
    logger.error('Invalid input: must be a valid file path or HTTP/HTTPS URL');
    process.exit(2);
  }

  try {
    logger.info('Starting code generation...');
    const project = new Project();
    const generatorOptions: GeneratorOptions = {
      inputPath: options.input,
      outputDir: options.output,
      project,
      logger,
    };
    const codeGenerator = new CodeGenerator(generatorOptions);
    await codeGenerator.generate();
    logger.success(
      `Code generation completed successfully! Files generated in: ${options.output}`,
    );
  } catch (error) {
    logger.error(`Error during code generation: ${error}`);
    process.exit(1);
  }
}
