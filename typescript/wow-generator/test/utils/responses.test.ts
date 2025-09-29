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

import { describe, expect, it } from 'vitest';
import { extractResponseJsonSchema } from '../../src/utils';
import { Response, Reference, Schema } from '@ahoo-wang/fetcher-openapi';
import { ContentTypeValues } from '@ahoo-wang/fetcher';

describe('responses', () => {
  describe('extractOkResponseJsonSchema', () => {
    it('should return the JSON schema from OK response', () => {
      const schema: Schema = { type: 'object' };
      const okResponse: Response = {
        description: 'OK',
        content: {
          [ContentTypeValues.APPLICATION_JSON]: { schema },
        },
      };

      expect(extractResponseJsonSchema(okResponse)).toBe(schema);
    });

    it('should return undefined if response is undefined', () => {
      expect(extractResponseJsonSchema(undefined)).toBeUndefined();
    });

    it('should return undefined if response is a reference', () => {
      const reference: Reference = { $ref: '#/components/responses/OK' };
      expect(extractResponseJsonSchema(reference)).toBeUndefined();
    });

    it('should return undefined if response has no content', () => {
      const okResponse: Response = { description: 'OK' };
      expect(extractResponseJsonSchema(okResponse)).toBeUndefined();
    });

    it('should return undefined if response has no JSON content', () => {
      const okResponse: Response = {
        description: 'OK',
        content: {
          'text/plain': { schema: { type: 'string' } },
        },
      };

      expect(extractResponseJsonSchema(okResponse)).toBeUndefined();
    });
  });
});
