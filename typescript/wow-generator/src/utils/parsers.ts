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
import { loadResource } from './resources';

/**
 * Parses an OpenAPI specification from a file path.
 *
 * @param inputPath - The path to the OpenAPI specification file
 * @returns A promise that resolves to the parsed OpenAPI object
 */
export async function parseOpenAPI(inputPath: string): Promise<OpenAPI> {
  return parseContent<OpenAPI>(await loadResource(inputPath));
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
