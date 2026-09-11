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

import type { StoryObj } from '@storybook/react-vite';
import displayMeta, {
  Workbench,
  NarrowDark as DisplayNarrowDark,
} from './Lifecycle.stories.js';
import { completeOrder } from './lifecycle.play.js';
import { playNarrowDark } from '../libraryDelivery.play.js';
const meta = {
  ...displayMeta,
  id: 'view-engine-全链路体验-回归',
  title: 'View Engine/入门与业务流程/全链路体验/回归',
  tags: ['!dev', '!autodocs', 'test'],
};
export default meta;
export const Complete: StoryObj<typeof meta> = {
  ...Workbench,
  play: completeOrder,
};

export const NarrowDark: StoryObj<typeof meta> = {
  ...DisplayNarrowDark,
  play: playNarrowDark,
};
