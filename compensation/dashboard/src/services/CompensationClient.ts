import axios from 'axios';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api';

interface ApplyRetrySpecRequest {
  id: string;
  spec: {
    maxRetries: number;
    backoffPolicy: 'exponential' | 'fixed';
    backoffDelay: number;
    retryConditions: string[];
  };
}

interface ChangeFunctionRequest {
  id: string;
  function: string;
}

interface MarkRecoverableRequest {
  id: string;
}

export const CompensationClient = {
  getExecutionFailedList: async (status: string, page: number, size: number) => {
    const response = await axios.get(`${API_BASE_URL}/execution-failed`, {
      params: { status, page, size }
    });
    return response.data;
  },

  applyRetrySpec: async (request: ApplyRetrySpecRequest) => {
    await axios.post(`${API_BASE_URL}/apply-retry-spec`, request);
  },

  changeFunction: async (request: ChangeFunctionRequest) => {
    await axios.post(`${API_BASE_URL}/change-function`, request);
  },

  markRecoverable: async (request: MarkRecoverableRequest) => {
    await axios.post(`${API_BASE_URL}/mark-recoverable`, request);
  },

  getExecutionHistory: async (id: string) => {
    const response = await axios.get(`${API_BASE_URL}/execution-failed/${id}/history`);
    return response.data;
  }
};
