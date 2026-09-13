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
import { useEffect, useRef, useState, type ReactNode } from 'react';
import GridLayout, {
  useContainerWidth,
  type Layout,
  type EventCallback,
} from 'react-grid-layout';
import { GripVerticalIcon, ScalingIcon } from 'lucide-react';
import { Button } from '../components/ui/button.js';
import type { DashboardPanel } from './dashboardModel.js';
import type { DeepReadonly } from '../lib/types.js';
import {
  applyGridLayout,
  toGridLayout,
  updatePanelLayout,
} from './dashboardLayout.js';

type Panels = readonly DeepReadonly<DashboardPanel>[];
export interface DashboardLayoutProps {
  panels: Panels;
  enabled: boolean;
  onCommit(panels: Panels): void;
  title(panelId: string): string;
  children(panel: DeepReadonly<DashboardPanel>): ReactNode;
}
/** RGL owns geometry and gesture preview; only completed gestures reach the runtime. */
export function DashboardLayout(props: DashboardLayoutProps) {
  const { width, containerRef } = useContainerWidth({ initialWidth: 1200 });
  const mobile = width < 640;
  const enabled = props.enabled && !mobile;
  const gesture = useRef<{
    panels: Panels;
    canceled: boolean;
    touch?: Touch;
    document: Document;
  } | null>(null);
  const [activePanel, setActivePanel] =
    useState<DeepReadonly<DashboardPanel> | null>(null);
  const retainedPanel =
    activePanel && !props.panels.some(panel => panel.id === activePanel.id)
      ? activePanel
      : null;
  const renderedPanels = retainedPanel
    ? [...props.panels, retainedPanel]
    : props.panels;
  const [announcement, setAnnouncement] = useState('');
  const [rollback, setRollback] = useState<{ layout: Layout } | null>(null);
  useEffect(() => {
    if (rollback) queueMicrotask(() => setRollback(null));
  }, [rollback]);
  const start: EventCallback = (
    _layout,
    _old,
    item,
    _placeholder,
    event,
    element,
  ) => {
    if (!enabled) return;
    gesture.current = {
      panels: props.panels,
      canceled: false,
      touch: 'touches' in event ? (event as TouchEvent).touches[0] : undefined,
      document: element?.ownerDocument ?? document,
    };
    setActivePanel(props.panels.find(panel => panel.id === item?.i) ?? null);
    setAnnouncement(`已拿起${props.title(item?.i ?? '')}，按 Escape 取消`);
  };
  const stop: EventCallback = layout => {
    const current = gesture.current;
    gesture.current = null;
    setActivePanel(null);
    if (!current) return;
    if (current.canceled || !enabled || current.panels !== props.panels) {
      // RGL compares controlled layouts deeply: acknowledge the stopped preview,
      // then restore authoritative geometry after its active gesture clears.
      setRollback({ layout });
      setAnnouncement('已取消布局调整');
      return;
    }
    try {
      const next = applyGridLayout(props.panels, layout);
      if (next !== props.panels) props.onCommit(next);
      setAnnouncement('布局已调整');
    } catch {
      setRollback({ layout });
      setAnnouncement('尺寸超出允许范围，已恢复布局');
    }
  };
  useEffect(() => {
    function cancel() {
      const current = gesture.current;
      if (!current) return;
      current.canceled = true;
      if (current.touch) {
        const event = new Event('touchend', { bubbles: true });
        Object.defineProperty(event, 'changedTouches', {
          value: [current.touch],
        });
        Object.defineProperty(event, 'touches', { value: [] });
        current.document.dispatchEvent(event);
      } else
        current.document.dispatchEvent(
          new MouseEvent('mouseup', { bubbles: true }),
        );
    }
    function keydown(event: KeyboardEvent) {
      if (event.key === 'Escape' && gesture.current) {
        event.preventDefault();
        cancel();
      }
    }
    document.addEventListener('keydown', keydown);
    window.addEventListener('blur', cancel);
    return () => {
      cancel();
      document.removeEventListener('keydown', keydown);
      window.removeEventListener('blur', cancel);
    };
  }, [enabled, width, props.panels]);
  function change(
    panel: DeepReadonly<DashboardPanel>,
    values: Partial<DashboardPanel['layout']>,
  ) {
    if (!props.enabled) return;
    try {
      const next = updatePanelLayout(props.panels, panel.id, {
        ...panel.layout,
        ...values,
      });
      if (next !== props.panels) props.onCommit(next);
      setAnnouncement(`${props.title(panel.id)}布局已调整`);
    } catch {
      setAnnouncement('位置或尺寸超出允许范围');
    }
  }
  return (
    <div
      ref={containerRef}
      data-dashboard-grid=""
      className="fve-dashboard-grid"
      data-editing={enabled}
      data-mobile={mobile}
    >
      {props.enabled && mobile && (
        <p className="fve:text-sm fve:text-muted-foreground">
          窄屏按顺序阅读；聚焦移动或尺寸按钮后，使用方向键调整桌面布局。
        </p>
      )}
      <GridLayout
        width={width}
        layout={rollback?.layout ?? toGridLayout(renderedPanels)}
        gridConfig={{
          cols: 12,
          rowHeight: 32,
          margin: [16, 16],
          containerPadding: [0, 0],
          maxRows: 10000,
        }}
        dragConfig={{
          enabled,
          handle: '.fve-dashboard-drag',
          cancel: 'input,select,textarea,a,[contenteditable=true]',
          threshold: 6,
        }}
        resizeConfig={{ enabled, handles: ['se'] }}
        onDragStart={start}
        onDragStop={stop}
        onResizeStart={start}
        onResizeStop={stop}
      >
        {[...renderedPanels]
          .sort((a, b) => a.layout.y - b.layout.y || a.layout.x - b.layout.x)
          .map((panel, index) =>
            panel === retainedPanel ? (
              // Keep the native gesture listener mounted until cancellation reaches RGL.
              // Removed business content must disappear immediately, including its title.
              <section
                key={panel.id}
                aria-hidden="true"
                style={{ visibility: 'hidden' }}
                data-dashboard-cancel-shell=""
              />
            ) : (
              <section
                key={panel.id}
                data-dashboard-panel={panel.id}
                aria-label={`面板 ${index + 1}：${props.title(panel.id)}`}
                className="fve-dashboard-panel fve:rounded-xl fve:border fve:bg-card fve:text-card-foreground fve:shadow-sm"
              >
                <div
                  hidden={!props.enabled}
                  className="fve-dashboard-controls"
                  role="group"
                  aria-label={`${props.title(panel.id)}布局`}
                >
                  <Button
                    className="fve-dashboard-drag fve:touch-none"
                    variant="ghost"
                    size="icon"
                    aria-label={`移动${props.title(panel.id)}`}
                    title="拖动移动；方向键调整位置"
                    onKeyDown={event => {
                      const { x, y, w, h } = panel.layout;
                      const delta = {
                        ArrowLeft: { x: Math.max(0, x - 1) },
                        ArrowRight: { x: Math.min(12 - w, x + 1) },
                        ArrowUp: { y: Math.max(0, y - 1) },
                        ArrowDown: { y: Math.min(10000 - h, y + 1) },
                      }[event.key];
                      if (delta) {
                        event.preventDefault();
                        change(panel, delta);
                      }
                    }}
                  >
                    <GripVerticalIcon aria-hidden="true" />
                  </Button>
                  <Button
                    variant="ghost"
                    size="icon"
                    aria-label={`调整${props.title(panel.id)}尺寸`}
                    title="左右键调整宽度，上下键调整高度"
                    onKeyDown={event => {
                      const { x, y, w, h } = panel.layout;
                      const delta = {
                        ArrowLeft: { w: Math.max(1, w - 1) },
                        ArrowRight: { w: Math.min(12 - x, w + 1) },
                        ArrowUp: { h: Math.max(1, h - 1) },
                        ArrowDown: { h: Math.min(100, 10000 - y, h + 1) },
                      }[event.key];
                      if (delta) {
                        event.preventDefault();
                        change(panel, delta);
                      }
                    }}
                  >
                    <ScalingIcon aria-hidden="true" />
                  </Button>
                </div>
                <div className="fve-dashboard-body">
                  {props.children(panel)}
                </div>
              </section>
            ),
          )}
      </GridLayout>
      <p className="fve:sr-only" role="status" aria-live="polite">
        {announcement}
      </p>
    </div>
  );
}
