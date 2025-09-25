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
import { readFileSync } from 'fs';
import { parse } from 'yaml';

export interface OpenAPIParser {
  parse(inputPath: string): OpenAPI | null;
}

export class JsonOpenAPIParser implements OpenAPIParser {
  parse(inputPath: string): OpenAPI | null {
    if (!inputPath.endsWith('.json')) {
      return null;
    }
    const content = readFileSync(inputPath, 'utf-8');
    return JSON.parse(content);
  }
}

export class YamlOpenAPIParser implements OpenAPIParser {
  parse(inputPath: string): OpenAPI | null {
    if (!inputPath.endsWith('.yaml') && !inputPath.endsWith('.yml')) {
      return null;
    }
    const content = readFileSync(inputPath, 'utf-8');

    return parse(content);
  }
}

export class OpenAPIParserChain implements OpenAPIParser {
  private readonly parsers: OpenAPIParser[] = [new JsonOpenAPIParser(), new YamlOpenAPIParser()];

  parse(inputPath: string): OpenAPI | null {
    for (const parser of this.parsers) {
      const openAPI = parser.parse(inputPath);
      if (openAPI) {
        return openAPI;
      }
    }
    return null;
  }
}

export const openAPIParser = new OpenAPIParserChain();