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
import { useId, type ReactNode } from 'react';

interface ScenarioFrameProps {
  title: string;
  domain: string;
  summary: string;
  fixture: string;
  setup: string;
  observe: string;
  children: ReactNode;
}

export function ScenarioFrame({
  title,
  domain,
  summary,
  fixture,
  setup,
  observe,
  children,
}: ScenarioFrameProps) {
  const headingId = useId();
  return (
    <article aria-labelledby={headingId} className="story-scene">
      <header className="story-scene-header">
        <p className="story-scene-domain">{domain}</p>
        <h2 id={headingId}>{title}</h2>
        <p className="story-scene-summary">{summary}</p>
        <p className="story-scene-fixture">Fixture · {fixture}</p>
      </header>
      <ol aria-label="Scenario contract" className="story-scene-contract">
        <li>
          <strong>Setup</strong>
          <span>{setup}</span>
        </li>
        <li>
          <strong>Action</strong>
          <span>Run the “{title}” scenario.</span>
        </li>
        <li>
          <strong>Observe</strong>
          <span>{observe}</span>
        </li>
      </ol>
      <div className="story-scene-stage">{children}</div>
    </article>
  );
}
