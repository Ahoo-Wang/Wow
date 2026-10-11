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

import { Link } from 'react-router';
import { useViewNavigation } from '@ahoo-wang/wow-view-engine/ui';

/** The datasets and the boards, each with its saved views. */
export default function HomePage() {
  const navigation = useViewNavigation();
  const datasets = navigation.filter(item => item.kind === 'data');
  const boards = navigation.filter(item => item.kind === 'dashboard');
  return (
    <div className="home">
      <section aria-labelledby="datasets">
        <h2 id="datasets">数据集</h2>
        {datasets.length === 0 ? (
          <p className="muted">还没有发布的数据集。</p>
        ) : (
          <ul className="cards">
            {datasets.map(item => (
              <li key={item.id}>
                <Link to={item.path}>{item.title}</Link>
                {item.views.length > 0 ? (
                  <ul>
                    {item.views.map(view => (
                      <li key={view.id}>
                        <Link to={view.path}>{view.title}</Link>
                      </li>
                    ))}
                  </ul>
                ) : null}
              </li>
            ))}
          </ul>
        )}
      </section>
      <section aria-labelledby="boards">
        <h2 id="boards">看板</h2>
        <ul className="cards">
          {boards.flatMap(item => [
            <li key={item.id}>
              <Link to={item.path}>新建或打开看板</Link>
            </li>,
            ...item.views.map(view => (
              <li key={view.id}>
                <Link to={view.path}>{view.title}</Link>
              </li>
            )),
          ])}
        </ul>
      </section>
    </div>
  );
}
