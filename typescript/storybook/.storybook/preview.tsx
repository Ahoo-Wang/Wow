import type { Preview } from '@storybook/react-vite';
import { App, ConfigProvider } from 'antd';
import './preview.css';

const preview: Preview = {
  decorators: [
    Story => (
      <App>
        <ConfigProvider>
          <Story />
        </ConfigProvider>
      </App>
    ),
  ],
  parameters: {
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
        order: [
          'Introduction',
          'Fetcher',
          'Decorator',
          'EventStream',
          'OpenAI',
          'EventBus',
          'Storage',
          'React',
          'Wow',
          'Generator',
          'CoSec',
          'OpenAPI',
          'Viewer',
        ],
      },
    },
  },
  tags: ['autodocs'],
};

export default preview;
