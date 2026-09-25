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
import { parse } from 'yaml';
import { errorMessage, GeneratorError } from '../api/errors';
import type { LoadResourceOptions } from './resources';
import { loadResource } from './resources';

/**
 * Loads and parses an OpenAPI 3 document.
 *
 * Every failure is a {@link GeneratorError} of kind `input` whose message
 * names the document: it cannot be read or fetched, it is neither JSON nor
 * YAML, or it is not an OpenAPI 3 document. A Swagger 2.0 document is
 * refused rather than generated into clients without models.
 *
 * @param inputPath - The path or http(s) URL of the document
 * @param options - Headers and timeout for an http(s) document
 * @returns The parsed document; `paths` is an empty object when it has none
 */
export async function parseOpenAPI(
  inputPath: string,
  options?: LoadResourceOptions,
): Promise<OpenAPI> {
  let content: string;
  try {
    content = await loadResource(inputPath, options);
  } catch (error) {
    throw new GeneratorError(
      'input',
      `Cannot read the OpenAPI document ${inputPath}: ${errorMessage(error)}`,
      { cause: error },
    );
  }
  let document: unknown;
  try {
    document = parseContent(content);
  } catch (error) {
    throw new GeneratorError(
      'input',
      `Cannot parse the OpenAPI document ${inputPath}: ${errorMessage(error)}`,
      { cause: error },
    );
  }
  return validateOpenAPIDocument(document, inputPath);
}

/**
 * Checks that a parsed document is an OpenAPI 3 document the generator reads.
 *
 * @param document - The parsed document
 * @param source - Where it came from, for messages
 * @returns The document, with `paths` defaulted to an empty object
 * @throws GeneratorError when it is not an OpenAPI 3.x document
 */
export function validateOpenAPIDocument(
  document: unknown,
  source: string,
): OpenAPI {
  if (
    typeof document !== 'object' ||
    document === null ||
    Array.isArray(document)
  ) {
    throw new GeneratorError(
      'input',
      `${source} is not an OpenAPI document: expected an object at the top level.`,
    );
  }
  const candidate = document as Record<string, unknown>;
  if (candidate.swagger !== undefined) {
    throw new GeneratorError(
      'input',
      `${source} is a Swagger ${String(candidate.swagger)} document; wow-generator reads OpenAPI 3.x only. Convert it first, for example with swagger2openapi.`,
    );
  }
  if (
    typeof candidate.openapi !== 'string' ||
    !/^3\.\d+/.test(candidate.openapi)
  ) {
    throw new GeneratorError(
      'input',
      `${source} is not an OpenAPI 3.x document: its "openapi" field is ${candidate.openapi === undefined ? 'missing' : JSON.stringify(candidate.openapi)}.`,
    );
  }
  if (
    typeof candidate.info !== 'object' ||
    candidate.info === null ||
    Array.isArray(candidate.info)
  ) {
    throw new GeneratorError(
      'input',
      `${source} is not a valid OpenAPI document: its "info" object is missing.`,
    );
  }
  // OpenAPI 3.1 allows a document without paths (webhooks or components only).
  return { ...candidate, paths: candidate.paths ?? {} } as OpenAPI;
}

/**
 * Parses already-loaded document content in whichever format it is written.
 *
 * Callers name the source in their own error, so a failure here carries only
 * what went wrong with the text.
 *
 * @param content - The document text
 * @returns The parsed document
 */
export function parseContent<T>(content: string): T {
  // inferFileFormat answers with a format or throws, so the switch is
  // exhaustive - a `default` here would be a branch no input can reach.
  switch (inferFileFormat(content)) {
    case FileFormat.JSON:
      return JSON.parse(content);
    case FileFormat.YAML:
      return parse(content);
  }
}

export enum FileFormat {
  JSON = 'json',
  YAML = 'yaml',
}

export function inferFileFormat(content: string): FileFormat {
  // Trim whitespace and BOM characters from the beginning
  const trimmedContent = content.trimStart();

  if (trimmedContent.startsWith('{') || trimmedContent.startsWith('[')) {
    return FileFormat.JSON;
  }

  // YAML can start with various characters, but commonly with '-' (for arrays) or '%YAML'
  // We'll check for common YAML indicators
  if (trimmedContent.startsWith('-') || trimmedContent.startsWith('%YAML')) {
    return FileFormat.YAML;
  }

  // Try to parse as JSON to see if it's valid JSON despite not starting with { or [
  try {
    JSON.parse(trimmedContent);
    return FileFormat.JSON;
  } catch {
    // If it's not valid JSON, we'll assume it's YAML if it's not empty
    if (trimmedContent.length > 0) {
      return FileFormat.YAML;
    }
  }

  throw new Error('Unable to infer file format');
}
