/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { ExampleViewPage } from '../../../packages/view-engine/examples/react/ExampleViewPage.js';
import {
  createContext,
  useContext,
  useEffect,
  useLayoutEffect,
  useState,
} from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import {
  TextCell,
  Button,
  type CellRendererProps,
} from '@ahoo-wang/fetcher-view-engine/react';
import '@ahoo-wang/fetcher-view-engine/styles.css';
import {
  createReadinessService,
  type ReadinessService,
} from './readinessFixture.js';

const Service = createContext<ReadinessService | null>(null);
declare global {
  interface Window {
    __viewReadiness?: ReadinessService;
  }
}
function MeasuredText({ value }: CellRendererProps) {
  const service = useContext(Service)!;
  useLayoutEffect(() => service.recordCellCommit());
  return <TextCell value={value} />;
}
const extensions = { cells: { 'measured-text': MeasuredText } };

function ReadinessWorkbench() {
  const [service] = useState(createReadinessService);
  const [mounted, setMounted] = useState(true);
  const [appearance, setAppearance] = useState<'light' | 'dark'>('light');
  useEffect(() => {
    window.__viewReadiness = service;
    return () => {
      delete window.__viewReadiness;
    };
  }, [service]);
  return (
    <Service value={service}>
      <div
        className="fve-root"
        data-testid="readiness-workbench"
        data-theme={appearance}
        style={{
          background: 'var(--fve-background)',
          color: 'var(--fve-foreground)',
          minHeight: '100vh',
          padding: 12,
        }}
      >
        <div
          role="group"
          aria-label="验收场景控制"
          style={{
            display: 'flex',
            flexWrap: 'wrap',
            gap: 8,
            marginBottom: 12,
          }}
        >
          <Button variant="outline" onClick={() => setMounted(value => !value)}>
            {mounted ? '卸载视图' : '挂载视图'}
          </Button>
          <Button
            variant="outline"
            onClick={() =>
              setAppearance(value => (value === 'light' ? 'dark' : 'light'))
            }
          >
            切换验收主题
          </Button>
        </div>
        {mounted && (
          <ExampleViewPage
            scopeKey="readiness:local"
            definitionId={service.definition.id}
            definition={service.definition}
            instances={service.instances}
            host={service.host}
            extensions={extensions}
            selectable
            initialSidebarCollapsed
          />
        )}
      </div>
    </Service>
  );
}
const meta = {
  title: '开发验证/数据视图验收',
  id: 'development-view-engine-readiness',
  component: ReadinessWorkbench,
  tags: ['!autodocs', '!test'],
  parameters: { layout: 'fullscreen' },
} satisfies Meta<typeof ReadinessWorkbench>;
export default meta;
type Story = StoryObj<typeof meta>;
export const Workbench: Story = { name: '100 行 · 30 列 · 100 筛选字段' };
