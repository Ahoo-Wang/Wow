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
import { useContext } from 'react';
import {
  Controls,
  Description,
  DocsContext,
  Primary,
  Title,
} from '@storybook/addon-docs/blocks';

/** Render one example; keep the rest available without mounting every scene. */
export function DocsPage() {
  const context = useContext(DocsContext);
  const stories = context
    .componentStories()
    .filter(
      story => story.tags.includes('dev') && story.tags.includes('autodocs'),
    );
  return (
    <>
      <Title />
      <Description />
      <Primary />
      <Controls />
      <h2>独立场景</h2>
      <p>选择场景，在独立画布中操作。各场景使用自己的初始状态。</p>
      <nav aria-label="独立场景">
        <ul>
          {stories.map(story => (
            <li key={story.id}>
              <a
                href={`./?path=/story/${encodeURIComponent(story.id)}`}
                target="_top"
              >
                {story.name}
              </a>
            </li>
          ))}
        </ul>
      </nav>
    </>
  );
}
