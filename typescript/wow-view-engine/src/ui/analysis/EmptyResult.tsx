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

import { SigmaIcon } from 'lucide-react';
import {
  Empty,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from '../components/empty.js';
import { useViewMessages } from '../MessagesProvider.js';

/**
 * An aggregation that matched no group, said once for both layouts.
 *
 * The table drew this sentence and the chart drew an empty pair of axes — a
 * drawing of nothing, which reads as a chart that failed rather than as a
 * range nothing fell into. The layouts answer the same question, so they say
 * the same sentence, and it says what happened to the *range* rather than to
 * the analysis: 「没有符合条件的组」, not "nothing to aggregate".
 */
export function AnalysisEmpty() {
  const messages = useViewMessages();
  return (
    <Empty>
      <EmptyHeader>
        <EmptyMedia variant="icon">
          <SigmaIcon />
        </EmptyMedia>
        <EmptyTitle>{messages.label('label.analysis.empty')}</EmptyTitle>
      </EmptyHeader>
    </Empty>
  );
}
