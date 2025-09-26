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
import {
  extractComponentKey,
  extractSchema,
  extractResponse,
  extractRequestBody,
  extractParameter,
  keySchema,
  COMPONENTS_PREFIX,
  COMPONENTS_HEADERS_REF,
  COMPONENTS_PARAMETERS_REF,
  COMPONENTS_REQUEST_BODIES_REF,
  COMPONENTS_RESPONSES_REF,
  COMPONENTS_SCHEMAS_REF,
} from '@/utils/components.ts';
import {
  Components,
  Reference,
  Schema,
  Response,
  RequestBody,
  Parameter,
} from '@ahoo-wang/fetcher-openapi';

describe('components', () => {
  describe('constants', () => {
    it('should have correct component prefix', () => {
      expect(COMPONENTS_PREFIX).toBe('#/components/');
    });

    it('should have correct headers reference', () => {
      expect(COMPONENTS_HEADERS_REF).toBe('#/components/headers/');
    });

    it('should have correct parameters reference', () => {
      expect(COMPONENTS_PARAMETERS_REF).toBe('#/components/parameters/');
    });

    it('should have correct request bodies reference', () => {
      expect(COMPONENTS_REQUEST_BODIES_REF).toBe('#/components/requestBodies/');
    });

    it('should have correct responses reference', () => {
      expect(COMPONENTS_RESPONSES_REF).toBe('#/components/responses/');
    });

    it('should have correct schemas reference', () => {
      expect(COMPONENTS_SCHEMAS_REF).toBe('#/components/schemas/');
    });
  });

  describe('extractComponentKey', () => {
    it('should extract the last part of the reference path', () => {
      const reference: Reference = { $ref: '#/components/schemas/User' };
      expect(extractComponentKey(reference)).toBe('User');
    });

    it('should handle nested paths', () => {
      const reference: Reference = {
        $ref: '#/components/schemas/User/Address',
      };
      expect(extractComponentKey(reference)).toBe('Address');
    });
  });

  describe('extractSchema', () => {
    it('should return the schema from components', () => {
      const components: Components = {
        schemas: {
          User: { type: 'object' },
        },
      };
      const reference: Reference = { $ref: '#/components/schemas/User' };
      expect(extractSchema(reference, components)).toEqual({ type: 'object' });
    });

    it('should return undefined if schema not found', () => {
      const components: Components = {};
      const reference: Reference = { $ref: '#/components/schemas/User' };
      expect(extractSchema(reference, components)).toBeUndefined();
    });
  });

  describe('extractResponse', () => {
    it('should return the response from components', () => {
      const response: Response = { description: 'OK' };
      const components: Components = {
        responses: {
          OK: response,
        },
      };
      const reference: Reference = { $ref: '#/components/responses/OK' };
      expect(extractResponse(reference, components)).toBe(response);
    });

    it('should return undefined if response not found', () => {
      const components: Components = {};
      const reference: Reference = { $ref: '#/components/responses/OK' };
      expect(extractResponse(reference, components)).toBeUndefined();
    });
  });

  describe('extractRequestBody', () => {
    it('should return the request body from components', () => {
      const requestBody: RequestBody = {
        description: 'User data',
        content: {},
      };
      const components: Components = {
        requestBodies: {
          UserData: requestBody,
        },
      };
      const reference: Reference = {
        $ref: '#/components/requestBodies/UserData',
      };
      expect(extractRequestBody(reference, components)).toBe(requestBody);
    });

    it('should return undefined if request body not found', () => {
      const components: Components = {};
      const reference: Reference = {
        $ref: '#/components/requestBodies/UserData',
      };
      expect(extractRequestBody(reference, components)).toBeUndefined();
    });
  });

  describe('extractParameter', () => {
    it('should return the parameter from components', () => {
      const parameter: Parameter = { name: 'id', in: 'path' };
      const components: Components = {
        parameters: {
          IdParam: parameter,
        },
      };
      const reference: Reference = { $ref: '#/components/parameters/IdParam' };
      expect(extractParameter(reference, components)).toBe(parameter);
    });

    it('should return undefined if parameter not found', () => {
      const components: Components = {};
      const reference: Reference = { $ref: '#/components/parameters/IdParam' };
      expect(extractParameter(reference, components)).toBeUndefined();
    });
  });

  describe('keySchema', () => {
    it('should return KeySchema with key and schema', () => {
      const schema: Schema = { type: 'object' };
      const components: Components = {
        schemas: {
          User: schema,
        },
      };
      const reference: Reference = { $ref: '#/components/schemas/User' };
      const result = keySchema(reference, components);
      expect(result).toEqual({
        key: 'User',
        schema,
      });
    });
  });
});
