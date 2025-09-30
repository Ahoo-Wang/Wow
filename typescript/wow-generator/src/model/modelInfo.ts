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

import { Reference } from '@ahoo-wang/fetcher-openapi';
import { Named } from '@ahoo-wang/fetcher-wow';
import { extractComponentKey, pascalCase } from '../utils';
import { IMPORT_WOW_PATH, WOW_TYPE_MAPPING } from './wowTypeMapping';

/**
 * Data Model Info
 */
export interface ModelInfo extends Named {
  name: string;
  path: string;
}

/**
 * Resolves model information from a schema key.
 *
 * This function parses a dot-separated schema key and extracts the model name and path.
 * It assumes that the model name is the first part that starts with an uppercase letter.
 * All parts before the model name are treated as the path.
 *
 * @example
 *
 * - "wow.api.BindingError" -> {path:'/wow/api',name:'BindingError'}
 * - "compensation.ApiVersion" -> {path:'/compensation',name:'ApiVersion'}
 * - "ai.AiMessage.Assistant" -> {path:'/ai',name:'AiMessageAssistant'}
 * - "Result" -> {path:'/',name:'Result'}
 *
 * @param schemaKey - The dot-separated schema key (e.g., "com.example.User")
 * @returns ModelInfo object containing the parsed name and path
 */
export function resolveModelInfo(schemaKey: string): ModelInfo {
  if (!schemaKey) {
    return { name: '', path: '/' };
  }
  const mappedType =
    WOW_TYPE_MAPPING[schemaKey as keyof typeof WOW_TYPE_MAPPING];
  if (mappedType) {
    return { name: mappedType, path: IMPORT_WOW_PATH };
  }

  const parts = schemaKey.split('.');
  let modelNameIndex = -1;

  // Find the first part that starts with an uppercase letter
  for (let i = 0; i < parts.length; i++) {
    if (parts[i] && /^[A-Z]/.test(parts[i])) {
      modelNameIndex = i;
      break;
    }
  }

  // Construct the path from parts before the model name
  const pathParts = parts.slice(0, modelNameIndex);
  const path = pathParts.length > 0 ? `/${pathParts.join('/')}` : '/';

  // Construct the model name from the remaining parts
  const nameParts = parts.slice(modelNameIndex);
  const name = pascalCase(nameParts);

  return { name, path };
}

export function resolveReferenceModelInfo(reference: Reference): ModelInfo {
  const componentKey = extractComponentKey(reference);
  return resolveModelInfo(componentKey);
}
