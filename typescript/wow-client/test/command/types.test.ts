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
import { CommandStage } from '../../src';

describe('Command Types', () => {
  describe('CommandStage', () => {
    it('should have all command stages', () => {
      expect(CommandStage.SENT).toBe('SENT');
      expect(CommandStage.PROCESSED).toBe('PROCESSED');
      expect(CommandStage.SNAPSHOT).toBe('SNAPSHOT');
      expect(CommandStage.PROJECTED).toBe('PROJECTED');
      expect(CommandStage.EVENT_HANDLED).toBe('EVENT_HANDLED');
      expect(CommandStage.SAGA_HANDLED).toBe('SAGA_HANDLED');
    });
  });
});
