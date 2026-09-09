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
import {
  playEmptySummary,
  playLoadingSummaries,
  playSummaries,
  playSummaryFailure,
} from './summaries.play.js';
import { playPinnedColumns } from './pinnedColumns.play.js';
import '@ahoo-wang/fetcher-view-engine/styles.css';
import displayMeta, {
  EmptySummary as DisplayEmptySummary,
  LoadingSummaries as DisplayLoadingSummaries,
  PinnedColumns as DisplayPinnedColumns,
  Summaries as DisplaySummaries,
  SummaryFailure as DisplaySummaryFailure,
} from './Table.stories.js';
import type { Story } from './demoTypes.js';

const meta = {
  ...displayMeta,
  title: 'View Engine/Record View/表格与汇总/回归',
  tags: ['!dev', '!autodocs', 'test'],
};

export default meta;

export const Summaries: Story = {
  ...DisplaySummaries,
  tags: ['!dev', '!autodocs', 'test'],
  play: playSummaries,
};

export const LoadingSummaries: Story = {
  ...DisplayLoadingSummaries,
  tags: ['!dev', '!autodocs', 'test'],
  play: playLoadingSummaries,
};

export const SummaryFailure: Story = {
  ...DisplaySummaryFailure,
  tags: ['!dev', '!autodocs', 'test'],
  play: playSummaryFailure,
};

export const PinnedColumns: Story = {
  ...DisplayPinnedColumns,
  tags: ['!dev', '!autodocs', 'test'],
  play: playPinnedColumns,
};

export const EmptySummary: Story = {
  ...DisplayEmptySummary,
  tags: ['!dev', '!autodocs', 'test'],
  play: playEmptySummary,
};
