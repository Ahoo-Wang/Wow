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
import { ResourceAttributionPathSpec, UrlPathParams } from '../../src';

describe('endpoints', () => {
  describe('ResourceAttributionPathSpec', () => {
    it('should define correct path specifications', () => {
      expect(ResourceAttributionPathSpec.NONE).toBe('');
      expect(ResourceAttributionPathSpec.TENANT).toBe('/tenant/{tenantId}');
      expect(ResourceAttributionPathSpec.OWNER).toBe('/owner/{ownerId}');
      expect(ResourceAttributionPathSpec.TENANT_OWNER).toBe('/tenant/{tenantId}/owner/{ownerId}');
    });
  });

  describe('UrlPathParams', () => {
    it('should allow tenantId, ownerId, and id properties', () => {
      const params: UrlPathParams = {
        tenantId: 'tenant-1',
        ownerId: 'owner-1',
        id: 'id-1',
      };

      expect(params.tenantId).toBe('tenant-1');
      expect(params.ownerId).toBe('owner-1');
      expect(params.id).toBe('id-1');
    });

    it('should allow additional custom properties', () => {
      const params: UrlPathParams = {
        tenantId: 'tenant-1',
        customParam: 'custom-value',
        anotherParam: 'another-value',
      };

      expect(params.tenantId).toBe('tenant-1');
      expect(params.customParam).toBe('custom-value');
      expect(params.anotherParam).toBe('another-value');
    });

    it('should allow undefined values', () => {
      const params: UrlPathParams = {
        tenantId: undefined,
        ownerId: undefined,
        id: undefined,
      };

      expect(params.tenantId).toBeUndefined();
      expect(params.ownerId).toBeUndefined();
      expect(params.id).toBeUndefined();
    });
  });
});