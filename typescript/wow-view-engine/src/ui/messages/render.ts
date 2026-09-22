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

/**
 * What a render boundary says where a part of the view failed to draw. The
 * error's own words are shown beside these, unlocalised — they are the
 * host's, and the host is who has to read them.
 */
export const renderMessages = {
  'label.render.failed': 'This part could not be drawn',
  // The rest of the view is still there and still works: the failure is
  // contained to the block the sentence is in, which is the whole point.
  'label.render.failed-hint':
    'The rest of the view still works. Try again to draw this part.',
  'label.render.retry': 'Try again',
} as const satisfies Record<string, string>;
