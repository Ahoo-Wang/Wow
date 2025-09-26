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

import { Reference, Schema, Response } from '@ahoo-wang/fetcher-openapi';
import { ContentTypeValues } from '@ahoo-wang/fetcher';
import { isReference } from '@/utils/references.ts';

/**
 * Extracts the JSON schema from an OK response.
 * @param okResponse - The response object or reference
 * @returns The JSON schema from the response content or undefined if not found
 */
export function extractOkResponseJsonSchema(
  okResponse?: Response | Reference,
): Schema | Reference | undefined {
  if (!okResponse) {
    return;
  }
  if (isReference(okResponse)) {
    return undefined;
  }

  if (!okResponse.content) {
    return undefined;
  }
  return okResponse.content[ContentTypeValues.APPLICATION_JSON].schema;
}
