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

import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import {
  PlusIcon,
  ChevronDownIcon,
  FileTextIcon,
  LinkIcon,
  ImageIcon,
  PanelsTopLeftIcon,
} from 'lucide-react';
import {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuItem,
} from '../components/ui/dropdown-menu.js';
import { Button } from '../components/ui/button.js';
import { Input } from '../components/ui/input.js';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
} from '../components/ui/dialog.js';
import type { DashboardCandidate } from '../contracts/ViewHost.js';
import type {
  DashboardRuntime,
  DashboardSnapshot,
} from './DashboardRuntime.js';
import type {
  DashboardContentPanel,
  DashboardPanel,
} from './dashboardModel.js';
import type { DeepReadonly } from '../lib/types.js';
import { safeUrl } from '../lib/safeUrl.js';
import { message, sameJsonState } from '../lib/snapshot.js';

export function DashboardSettings({
  runtime,
  snapshot,
  editing,
  toolbar,
}: {
  runtime: DashboardRuntime;
  snapshot: DashboardSnapshot;
  editing: boolean;
  toolbar: HTMLDivElement | null;
}) {
  const [selection, setSelection] = useState<{
    panel?: DeepReadonly<DashboardPanel>;
  } | null>(null);
  const [content, setContent] = useState<DashboardContentPanel | null>(null);
  const [originalContent, setOriginalContent] =
    useState<DashboardContentPanel | null>(null);
  const [contentError, setContentError] = useState<string | null>(null);
  const [query, setQuery] = useState('');
  const [cursor, setCursor] = useState<string>();
  const [retry, setRetry] = useState(0);
  const [items, setItems] = useState<DashboardCandidate[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const controls = useRef<HTMLDivElement>(null);
  const addRef = useRef<HTMLButtonElement>(null);
  const dialogFocus = useRef<HTMLElement | null>(null);
  const opened = selection !== null;
  useEffect(() => {
    if (!opened) return;
    const controller = new AbortController();
    void runtime.searchCandidates({ query, cursor }, controller.signal).then(
      result => {
        if (controller.signal.aborted) return;
        setItems(previous =>
          cursor
            ? [
                ...previous,
                ...result.items.filter(
                  item => !previous.some(old => old.id === item.id),
                ),
              ]
            : result.items,
        );
        setNextCursor(result.nextCursor);
        setError(null);
        setLoading(false);
      },
      reason => {
        if (controller.signal.aborted) return;
        setError(message(reason));
        setLoading(false);
      },
    );
    return () => controller.abort();
  }, [runtime, opened, query, cursor, retry]);
  function choose(panel?: DeepReadonly<DashboardPanel>) {
    dialogFocus.current =
      panel && document.activeElement instanceof HTMLElement
        ? document.activeElement
        : addRef.current;
    setSelection({ panel });
    setQuery('');
    setCursor(undefined);
    setItems([]);
    setNextCursor(null);
    setError(null);
    setLoading(true);
  }
  function select(candidate: DashboardCandidate) {
    try {
      if (!selection) return;
      const original = selection.panel;
      runtime.edit(config => {
        if (original) {
          const current = config.panels.find(panel => panel.id === original.id);
          if (
            !current ||
            !sameJsonState(
              { ...current, layout: null },
              { ...original, layout: null },
            )
          )
            throw new Error('面板已被移除或引用已更改，请取消并重新打开选择。');
        }
        return {
          ...config,
          panels: original
            ? config.panels.map(panel =>
                panel.id === original.id
                  ? {
                      id: panel.id,
                      kind: 'view' as const,
                      layout: panel.layout,
                      instanceId: candidate.id,
                    }
                  : panel,
              )
            : [
                ...config.panels,
                {
                  id: crypto.randomUUID(),
                  kind: 'view',
                  instanceId: candidate.id,
                  layout: {
                    x: 0,
                    y: Math.max(
                      0,
                      ...config.panels.map(
                        panel => panel.layout.y + panel.layout.h,
                      ),
                    ),
                    w: 6,
                    h: 18,
                  },
                },
              ],
        };
      });
      setSelection(null);
    } catch (reason) {
      setError(message(reason));
    }
  }
  function editContent(panel: DashboardContentPanel, existing = true) {
    dialogFocus.current =
      existing && document.activeElement instanceof HTMLElement
        ? document.activeElement
        : addRef.current;
    setOriginalContent(existing ? panel : null);
    setContent({ ...panel });
    setContentError(null);
  }
  function addContent(kind: DashboardContentPanel['kind']) {
    const base = {
      id: crypto.randomUUID(),
      title: '',
      layout: {
        x: 0,
        y: Math.max(
          0,
          ...snapshot.config.panels.map(
            panel => panel.layout.y + panel.layout.h,
          ),
        ),
        w: 6,
        h: 8,
      },
    };
    editContent(
      kind === 'markdown'
        ? { ...base, kind, content: '' }
        : kind === 'link'
          ? { ...base, kind, href: '', description: '' }
          : { ...base, kind, src: '', alt: '', caption: '' },
      false,
    );
  }
  if (!snapshot.editable) return null;
  return (
    <div className="fve:contents" ref={controls} tabIndex={-1}>
      {toolbar &&
        createPortal(
          <DropdownMenu>
            <DropdownMenuTrigger
              render={<Button ref={addRef} variant="outline" />}
            >
              <PlusIcon aria-hidden="true" />
              添加
              <ChevronDownIcon aria-hidden="true" />
            </DropdownMenuTrigger>
            <DropdownMenuContent
              align="end"
              className="fve:min-w-48"
              finalFocus={!opened && content === null}
            >
              {runtime.canDiscover && (
                <DropdownMenuItem onClick={() => choose()}>
                  <PanelsTopLeftIcon aria-hidden="true" />
                  添加面板
                </DropdownMenuItem>
              )}
              <DropdownMenuItem onClick={() => addContent('markdown')}>
                <FileTextIcon aria-hidden="true" />
                添加Markdown
              </DropdownMenuItem>
              <DropdownMenuItem onClick={() => addContent('link')}>
                <LinkIcon aria-hidden="true" />
                添加链接
              </DropdownMenuItem>
              <DropdownMenuItem onClick={() => addContent('image')}>
                <ImageIcon aria-hidden="true" />
                添加图片
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>,
          toolbar,
        )}
      {editing &&
        snapshot.config.panels.map((panel, index) => (
          <div
            key={panel.id}
            className="fve:flex fve:min-w-0 fve:flex-wrap fve:items-center fve:gap-2"
          >
            <span className="fve:min-w-0 fve:flex-1 fve:break-words fve:text-sm">
              {panel.kind === 'view'
                ? (snapshot.panels[panel.id]?.instance?.title ??
                  panel.instanceId)
                : panel.title}
            </span>
            {panel.kind !== 'view' && (
              <Button
                variant="outline"
                size="sm"
                aria-label={`编辑面板${index + 1}`}
                onClick={() => editContent(panel)}
              >
                编辑内容
              </Button>
            )}
            {panel.kind === 'view' && runtime.canDiscover && (
              <Button
                variant="outline"
                size="sm"
                onClick={() => choose(panel)}
                aria-label={`替换面板${index + 1}`}
              >
                替换引用
              </Button>
            )}
            <Button
              variant="ghost"
              size="sm"
              data-remove-panel={panel.id}
              onClick={() => {
                const buttons =
                  controls.current?.querySelectorAll<HTMLButtonElement>(
                    '[data-remove-panel]',
                  );
                const focus =
                  buttons?.[index + 1] ??
                  buttons?.[index - 1] ??
                  addRef.current ??
                  controls.current;
                try {
                  runtime.edit(config => ({
                    ...config,
                    panels: config.panels.filter(item => item.id !== panel.id),
                  }));
                  focus?.focus();
                } catch (reason) {
                  setError(message(reason));
                }
              }}
              aria-label={`移除面板${index + 1}`}
            >
              移除
            </Button>
          </div>
        ))}
      {error && !opened && <p role="alert">{error}</p>}
      <Dialog
        open={content !== null}
        onOpenChange={open => {
          if (!open) setContent(null);
        }}
      >
        <DialogContent finalFocus={dialogFocus}>
          <DialogHeader>
            <DialogTitle>
              {content &&
                { markdown: 'Markdown', link: '链接', image: '图片' }[
                  content.kind
                ]}
              面板
            </DialogTitle>
            <DialogDescription>
              编辑内容后确认添加或更新；取消不会修改仪表盘。
            </DialogDescription>
          </DialogHeader>
          {content && (
            <form
              className="fve:flex fve:flex-col fve:gap-3"
              onSubmit={event => {
                event.preventDefault();
                try {
                  if (!content.title.trim()) throw new Error('请填写标题');
                  if (content.kind === 'link' && !safeUrl(content.href))
                    throw new Error('请输入有效链接地址');
                  if (
                    content.kind === 'image' &&
                    !safeUrl(content.src, ['http:', 'https:'])
                  )
                    throw new Error('请输入有效图片地址（HTTP 或 HTTPS）');
                  runtime.edit(config => {
                    const current = config.panels.find(
                      panel => panel.id === content.id,
                    );
                    if (
                      originalContent
                        ? !current ||
                          !sameJsonState(
                            { ...current, layout: null },
                            { ...originalContent, layout: null },
                          )
                        : current
                    ) {
                      throw new Error(
                        '面板已被移除或内容已更改，请取消并重新打开编辑。',
                      );
                    }
                    return {
                      ...config,
                      panels: originalContent
                        ? config.panels.map(panel =>
                            panel.id === content.id
                              ? { ...content, layout: panel.layout }
                              : panel,
                          )
                        : [...config.panels, content],
                    };
                  });
                  setContent(null);
                } catch (reason) {
                  setContentError(message(reason));
                }
              }}
            >
              <label>
                标题
                <Input
                  required
                  value={content.title}
                  onChange={event =>
                    setContent({ ...content, title: event.target.value })
                  }
                />
              </label>
              {content.kind === 'markdown' && (
                <label>
                  Markdown 内容
                  <textarea
                    className="fve:w-full fve:rounded-md fve:border fve:p-2"
                    rows={8}
                    value={content.content}
                    onChange={event =>
                      setContent({ ...content, content: event.target.value })
                    }
                  />
                </label>
              )}
              {content.kind === 'link' && (
                <>
                  <label>
                    链接地址
                    <Input
                      required
                      value={content.href}
                      onChange={event =>
                        setContent({ ...content, href: event.target.value })
                      }
                    />
                  </label>
                  <label>
                    说明（可选）
                    <Input
                      value={content.description ?? ''}
                      onChange={event =>
                        setContent({
                          ...content,
                          description: event.target.value,
                        })
                      }
                    />
                  </label>
                </>
              )}
              {content.kind === 'image' && (
                <>
                  <label>
                    图片地址
                    <Input
                      required
                      value={content.src}
                      onChange={event =>
                        setContent({ ...content, src: event.target.value })
                      }
                    />
                  </label>
                  <label>
                    替代文字
                    <Input
                      value={content.alt}
                      onChange={event =>
                        setContent({ ...content, alt: event.target.value })
                      }
                    />
                  </label>
                  <label>
                    图注（可选）
                    <Input
                      value={content.caption ?? ''}
                      onChange={event =>
                        setContent({ ...content, caption: event.target.value })
                      }
                    />
                  </label>
                </>
              )}
              {contentError && <p role="alert">{contentError}</p>}
              <div className="fve:flex fve:justify-end fve:gap-2">
                <Button
                  type="button"
                  variant="outline"
                  onClick={() => setContent(null)}
                >
                  取消
                </Button>
                <Button type="submit">
                  {originalContent ? '更新内容' : '添加内容'}
                </Button>
              </div>
            </form>
          )}
        </DialogContent>
      </Dialog>
      <Dialog
        open={opened}
        onOpenChange={open => {
          if (!open) setSelection(null);
        }}
      >
        <DialogContent className="fve:sm:max-w-xl" finalFocus={dialogFocus}>
          <DialogHeader>
            <DialogTitle>
              {selection?.panel ? '替换面板引用' : '添加面板'}
            </DialogTitle>
            <DialogDescription>
              选择已保存的记录或分析视图。替换引用后需要重新确认全局筛选绑定。
            </DialogDescription>
          </DialogHeader>
          <label className="fve:flex fve:flex-col fve:gap-2">
            搜索视图
            <Input
              value={query}
              onChange={event => {
                setQuery(event.target.value);
                setCursor(undefined);
                setItems([]);
                setNextCursor(null);
                setLoading(true);
                setError(null);
              }}
            />
          </label>
          {loading && <p role="status">正在搜索视图…</p>}
          {error && (
            <div role="alert">
              <p>{error}</p>
              <Button
                variant="outline"
                onClick={() => {
                  setLoading(true);
                  setRetry(value => value + 1);
                }}
              >
                重试搜索
              </Button>
            </div>
          )}
          <ul className="fve:flex fve:max-h-80 fve:flex-col fve:gap-2 fve:overflow-auto">
            {items.map(candidate => (
              <li key={candidate.id}>
                <Button
                  variant="outline"
                  className="fve:h-auto fve:w-full fve:justify-start fve:whitespace-normal fve:py-3 fve:text-left"
                  onClick={() => select(candidate)}
                >
                  <span className="fve:min-w-0 fve:break-words">
                    {candidate.title}
                    <span className="fve:block fve:text-xs fve:text-muted-foreground">
                      {candidate.definitionId} ·{' '}
                      {candidate.kind === 'record' ? '记录' : '分析'}
                    </span>
                  </span>
                </Button>
              </li>
            ))}
          </ul>
          {!loading && !error && !items.length && (
            <p>没有匹配的可用视图，请调整搜索条件。</p>
          )}
          {nextCursor && (
            <Button
              variant="outline"
              disabled={loading}
              onClick={() => {
                setCursor(nextCursor);
                setLoading(true);
              }}
            >
              加载更多视图
            </Button>
          )}
        </DialogContent>
      </Dialog>
    </div>
  );
}
