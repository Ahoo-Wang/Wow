import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import '@testing-library/jest-dom';
import { ErrorDetails } from '../details/ErrorDetails.tsx';
import type { ErrorDetails as ErrorDetailsType } from '../../../services';

// Mock the Monaco Editor component since it's not easily testable in a jsdom environment
vi.mock('@monaco-editor/react', () => ({
  Editor: () => <div data-testid="monaco-editor">Monaco Editor</div>
}));

describe('ErrorDetails', () => {
  const mockErrorDetails: ErrorDetailsType = {
    errorCode: 'TEST_ERROR',
    errorMsg: 'This is a test error message',
    stackTrace: 'Error: This is a test error\n    at line 1\n    at line 2',
  };

  it('renders error code correctly', () => {
    render(<ErrorDetails error={mockErrorDetails} />);
    
    const errorCodeElement = screen.getByText('TEST_ERROR');
    expect(errorCodeElement).toBeInTheDocument();
    expect(errorCodeElement.tagName).toBe('CODE');
  });

  it('renders error message correctly', () => {
    render(<ErrorDetails error={mockErrorDetails} />);
    
    const errorMessageElement = screen.getByText('This is a test error message');
    expect(errorMessageElement).toBeInTheDocument();
  });

  it('renders stack trace section', () => {
    render(<ErrorDetails error={mockErrorDetails} />);
    
    const stackTraceTitle = screen.getByText('Stack Trace');
    expect(stackTraceTitle).toBeInTheDocument();
    
    const editorElement = screen.getByTestId('monaco-editor');
    expect(editorElement).toBeInTheDocument();
  });

  it('passes correct props to the editor', () => {
    render(<ErrorDetails error={mockErrorDetails} />);
    
    // Since we're mocking the editor, we can't directly test the props
    // But we can verify it renders
    const editorElement = screen.getByTestId('monaco-editor');
    expect(editorElement).toBeInTheDocument();
  });

  it('handles empty error details', () => {
    const emptyErrorDetails: ErrorDetailsType = {
      errorCode: '',
      errorMsg: '',
      stackTrace: '',
    };
    
    render(<ErrorDetails error={emptyErrorDetails} />);
    
    // Check that the component renders without crashing
    const stackTraceTitle = screen.getByText('Stack Trace');
    expect(stackTraceTitle).toBeInTheDocument();
    
    const editorElement = screen.getByTestId('monaco-editor');
    expect(editorElement).toBeInTheDocument();
  });
});