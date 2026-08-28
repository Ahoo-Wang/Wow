import type { Preview } from '@storybook/react-vite';
import { App, ConfigProvider } from 'antd';
import './preview.css';

interface SceneDefinition {
  domain: string;
  summary: string;
  fixture: string;
  setup: string;
  observe: string;
}

const sceneDefinitions: Record<string, SceneDefinition> = {
  'HTTP & Streaming/Event Bus': {
    domain: 'Event delivery',
    summary:
      'Compare handler order, completion, and cleanup across typed buses.',
    fixture: 'In-memory bus · deterministic handlers',
    setup: 'Named handlers are registered before each isolated run.',
    observe:
      'The result exposes delivery order, completion timing, or cleanup.',
  },
  'HTTP & Streaming/Event Stream': {
    domain: 'Streaming',
    summary: 'Read deterministic SSE frames as browser consumers receive them.',
    fixture: 'Local ReadableStream · fixed SSE chunks',
    setup: 'A Response is assembled from known event-stream chunks.',
    observe:
      'Parsed events, termination, malformed JSON, or cancellation is visible.',
  },
  'HTTP & Streaming/Fetcher': {
    domain: 'HTTP exchange',
    summary:
      'Trace a request through URL resolution, transport, and extraction.',
    fixture: 'Mock Service Worker · api.example.test',
    setup:
      'A Fetcher and deterministic HTTP fixture are created for this path.',
    observe:
      'The result names the URL, payload, timeout, or wrapped HTTP error.',
  },
  'HTTP & Streaming/OpenAI': {
    domain: 'Protocol streaming',
    summary: 'Reconstruct an OpenAI-compatible completion from local chunks.',
    fixture: 'Local SSE · no credentials',
    setup: 'Credential-free chat completion chunks form the response.',
    observe:
      'Token assembly, [DONE], malformed data, or cancellation is visible.',
  },
  'HTTP & Streaming/Storage': {
    domain: 'State persistence',
    summary: 'Exercise serialization, listeners, and lifecycle in isolation.',
    fixture: 'In-memory storage · disposable listeners',
    setup: 'A fresh key and serializer are created for the selected variant.',
    observe:
      'The result exposes values, updates, cleanup, or serializer failure.',
  },
  'React Hooks/Async State': {
    domain: 'React async state',
    summary: 'Observe one promise as it moves through the hook state machine.',
    fixture: 'Deterministic timers · isolated hook',
    setup: 'The hook starts idle with controlled completion timing.',
    observe:
      'Status and result show success, rejection, retry, or stale suppression.',
  },
  'React Hooks/Fetcher': {
    domain: 'React request state',
    summary: 'Bind a typed Fetcher request to React execution state.',
    fixture: 'Mock Service Worker · local users',
    setup:
      'A hook receives a deterministic request and local response fixture.',
    observe:
      'Loading, result, error, refresh, and cancellation remain inspectable.',
  },
  'React Hooks/Wow Queries': {
    domain: 'CQRS query state',
    summary:
      'Exercise typed Wow query shapes through their React hook adapters.',
    fixture: 'Local Wow responses · typed filters',
    setup: 'A query hook starts with deterministic aggregate data and filters.',
    observe: 'Single, list, page, count, and stream state stay visible.',
  },
};

const preview: Preview = {
  decorators: [
    (Story, context) => {
      const scene = sceneDefinitions[context.title];
      const story = scene ? (
        <article
          aria-labelledby={`story-scene-${context.id}`}
          className="story-scene"
        >
          <header className="story-scene-header">
            <p className="story-scene-domain">{scene.domain}</p>
            <h2 id={`story-scene-${context.id}`}>{context.name}</h2>
            <p className="story-scene-summary">{scene.summary}</p>
            <p className="story-scene-fixture">Fixture · {scene.fixture}</p>
          </header>
          <ol aria-label="Scenario contract" className="story-scene-contract">
            <li>
              <strong>Setup</strong>
              <span>{scene.setup}</span>
            </li>
            <li>
              <strong>Action</strong>
              <span>Run the “{context.name}” scenario.</span>
            </li>
            <li>
              <strong>Observe</strong>
              <span>{scene.observe}</span>
            </li>
          </ol>
          <div className="story-scene-stage">
            <Story />
          </div>
        </article>
      ) : (
        <Story />
      );

      return (
        <App>
          <ConfigProvider
            theme={{
              token: {
                colorErrorText: '#820014',
                colorLink: '#003a8c',
                colorPrimary: '#0958d9',
                colorSuccessText: '#135200',
                colorTextQuaternary: '#595959',
                colorTextSecondary: '#595959',
              },
            }}
          >
            {story}
          </ConfigProvider>
        </App>
      );
    },
  ],
  parameters: {
    a11y: {
      context: {
        exclude: [['.ant-select-dropdown'], ['.ant-table-measure-row']],
      },
      test: 'error',
    },
    controls: {
      matchers: {
        color: /(background|color)$/i,
        date: /Date$/i,
      },
    },
    docs: {
      codePanel: true,
      toc: true,
    },
    options: {
      storySort: {
        order: ['Overview', 'HTTP & Streaming', 'React Hooks', 'Viewer'],
      },
    },
  },
  tags: ['autodocs', 'test'],
};

export default preview;
