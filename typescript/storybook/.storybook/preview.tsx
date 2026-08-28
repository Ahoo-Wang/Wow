import type { Preview } from '@storybook/react-vite';
import { App, ConfigProvider } from 'antd';
import './preview.css';

const preview: Preview = {
  decorators: [
    Story => (
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
          <Story />
        </ConfigProvider>
      </App>
    ),
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
