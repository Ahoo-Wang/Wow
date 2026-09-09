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
import '@ahoo-wang/fetcher-view-engine/styles.css';
import {
  playCompleteFilter,
  playCustomEditor,
  playSearchableSelect,
} from './filterPanelExtensions.play.js';
import {
  playAdvancedTree,
  playAllOperators,
  playBusinessFilters,
  playSavedDateTime,
} from './filterPanelQuery.play.js';
import {
  playGroupedFields,
  playLogicalGroupMenu,
  playRepeatedFields,
} from './filterPanelSelection.play.js';
import displayMeta, {
  AdvancedTree as DisplayAdvancedTree,
  AllOperators as DisplayAllOperators,
  BusinessFilters as DisplayBusinessFilters,
  CompleteFilter as DisplayCompleteFilter,
  CustomEditor as DisplayCustomEditor,
  GroupedFields as DisplayGroupedFields,
  LogicalGroupMenu as DisplayLogicalGroupMenu,
  RepeatedFields as DisplayRepeatedFields,
  SavedDateTime as DisplaySavedDateTime,
  SearchableSelect as DisplaySearchableSelect,
} from './FilterPanel.stories.js';
import type { StoryObj as RegressionStoryObj } from '@storybook/react-vite';
import type { DemoArgs } from './FilterPanelExamples.js';

const meta = {
  ...displayMeta,
  title: 'View Engine/过滤器/回归',
  tags: ['!dev', '!autodocs', 'test'],
};

export default meta;

type Story = RegressionStoryObj<DemoArgs>;

export const GroupedFields: Story = {
  ...DisplayGroupedFields,
  tags: ['!dev', '!autodocs', 'test'],
  play: playGroupedFields,
};

export const BusinessFilters: Story = {
  ...DisplayBusinessFilters,
  tags: ['!dev', '!autodocs', 'test'],
  play: playBusinessFilters,
};

export const AdvancedTree: Story = {
  ...DisplayAdvancedTree,
  tags: ['!dev', '!autodocs', 'test'],
  play: playAdvancedTree,
};

export const LogicalGroupMenu: Story = {
  ...DisplayLogicalGroupMenu,
  tags: ['!dev', '!autodocs', 'test'],
  play: playLogicalGroupMenu,
};

export const RepeatedFields: Story = {
  ...DisplayRepeatedFields,
  tags: ['!dev', '!autodocs', 'test'],
  play: playRepeatedFields,
};

export const CustomEditor: Story = {
  ...DisplayCustomEditor,
  tags: ['!dev', '!autodocs', 'test'],
  play: playCustomEditor,
};

export const SearchableSelect: Story = {
  ...DisplaySearchableSelect,
  tags: ['!dev', '!autodocs', 'test'],
  play: playSearchableSelect,
};

export const CompleteFilter: Story = {
  ...DisplayCompleteFilter,
  tags: ['!dev', '!autodocs', 'test'],
  play: playCompleteFilter,
};

export const SavedDateTime: Story = {
  ...DisplaySavedDateTime,
  tags: ['!dev', '!autodocs', 'test'],
  play: playSavedDateTime,
};

export const AllOperators: Story = {
  ...DisplayAllOperators,
  tags: ['!dev', '!autodocs', 'test'],
  play: playAllOperators,
};
