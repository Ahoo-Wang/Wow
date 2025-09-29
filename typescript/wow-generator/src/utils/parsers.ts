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

import { OpenAPI } from '@ahoo-wang/fetcher-openapi';
import { parse } from 'yaml';
import { loadResource } from './resources';
import { GeneratorConfiguration } from '../types';

/**
 * Parses an OpenAPI specification from a file path.
 *
 * @param inputPath - The path to the OpenAPI specification file
 * @returns A promise that resolves to the parsed OpenAPI object
 */
export async function parseOpenAPI(inputPath: string): Promise<OpenAPI> {
  const content = await loadResource(inputPath);
  const fileFormat = inferFileFormat(content);
  switch (fileFormat) {
    case FileFormat.JSON:
      return JSON.parse(content);
    case FileFormat.YAML:
      return parse(content);
    default:
      throw new Error(`Unsupported file format: ${inputPath}`);
  }
}

export async function parseConfiguration(configPath: string): Promise<GeneratorConfiguration> {
  const content = await loadResource(configPath);
  const fileFormat = inferFileFormat(content);
  switch (fileFormat) {
    case FileFormat.JSON:
      return JSON.parse(content);
    case FileFormat.YAML:
      return parse(content);
    default:
      throw new Error(`Unsupported file format: ${configPath}`);
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
