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
import { playCompactWorkbench } from './workbench.play.js';
import {
  playNarrowRecords,
  playResponsiveColumns,
} from './responsiveLayout.play.js';
import { playDarkRecords, playThemeSwitching } from './themes.play.js';
import '@ahoo-wang/fetcher-view-engine/styles.css';
import displayMeta, {
  CompactWorkbench as DisplayCompactWorkbench,
  DarkRecords as DisplayDarkRecords,
  NarrowRecords as DisplayNarrowRecords,
  ResponsiveColumns as DisplayResponsiveColumns,
  ThemeSwitching as DisplayThemeSwitching,
} from './Layout.stories.js';
import type { Story } from './demoTypes.js';

const meta = {
  ...displayMeta,
  id: 'view-engine-专项场景-record-view-布局与主题-回归',
  title: 'View Engine/数据视图/布局与主题/回归',
  tags: ['!dev', '!autodocs', 'test'],
};

export default meta;

export const CompactWorkbench: Story = {
  ...DisplayCompactWorkbench,
  tags: ['!dev', '!autodocs', 'test'],
  play: playCompactWorkbench,
};

export const ResponsiveColumns: Story = {
  ...DisplayResponsiveColumns,
  tags: ['!dev', '!autodocs', 'test'],
  play: playResponsiveColumns,
};

export const DarkRecords: Story = {
  ...DisplayDarkRecords,
  tags: ['!dev', '!autodocs', 'test'],
  play: playDarkRecords,
};

export const ThemeSwitching: Story = {
  ...DisplayThemeSwitching,
  tags: ['!dev', '!autodocs', 'test'],
  play: playThemeSwitching,
};

export const NarrowRecords: Story = {
  ...DisplayNarrowRecords,
  tags: ['!dev', '!autodocs', 'test'],
  play: playNarrowRecords,
};
