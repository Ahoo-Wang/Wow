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

import { useState } from 'react';
import Markdown from 'react-markdown';
import { safeUrl } from '../lib/safeUrl.js';
import type { DeepReadonly } from '../lib/types.js';
import type { DashboardContentPanel } from './dashboardModel.js';

function ContentImage({ src, alt }: { src: string; alt: string }) {
  const [failed, setFailed] = useState(false);
  const url = safeUrl(src, ['http:', 'https:']);
  return !url || failed ? (
    <span role="alert">图片无法加载：{alt || '图片地址无效'}</span>
  ) : (
    <img src={url} alt={alt} loading="lazy" onError={() => setFailed(true)} />
  );
}

export function DashboardContent({
  panel,
}: {
  panel: DeepReadonly<DashboardContentPanel>;
}) {
  return (
    <article className="fve-dashboard-content" data-kind={panel.kind}>
      <h2>{panel.title}</h2>
      {panel.kind === 'markdown' ? (
        <Markdown
          skipHtml
          urlTransform={(url, key) =>
            safeUrl(url, key === 'src' ? ['http:', 'https:'] : undefined) ?? ''
          }
          components={{
            a: ({ href, children }) =>
              href ? (
                <a href={href} target="_blank" rel="noopener noreferrer">
                  {children}
                </a>
              ) : (
                <span>{children}</span>
              ),
            img: ({ src, alt }) => (
              <ContentImage
                key={typeof src === 'string' ? src : ''}
                src={typeof src === 'string' ? src : ''}
                alt={alt ?? ''}
              />
            ),
          }}
        >
          {panel.content}
        </Markdown>
      ) : panel.kind === 'link' ? (
        <>
          {safeUrl(panel.href) ? (
            <a
              href={safeUrl(panel.href)}
              target="_blank"
              rel="noopener noreferrer"
            >
              {panel.title}
            </a>
          ) : (
            <p role="alert">链接地址无效</p>
          )}
          {panel.description && <p>{panel.description}</p>}
        </>
      ) : (
        <figure>
          <ContentImage key={panel.src} src={panel.src} alt={panel.alt} />
          {panel.caption && <figcaption>{panel.caption}</figcaption>}
        </figure>
      )}
    </article>
  );
}
