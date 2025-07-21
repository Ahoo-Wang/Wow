import React, { createContext, useContext, useState, ReactNode } from 'react';
import { ExecutionFailedState } from '../types';

interface CompensationContextType {
  selectedExecution: ExecutionFailedState | null;
  setSelectedExecution: (execution: ExecutionFailedState | null) => void;
}

const CompensationContext = createContext<CompensationContextType | undefined>(undefined);

export const useCompensationContext = () => {
  const context = useContext(CompensationContext);
  if (!context) {
    throw new Error('useCompensationContext must be used within a CompensationProvider');
  }
  return context;
};

interface CompensationProviderProps {
  children: ReactNode;
}

export const CompensationProvider: React.FC<CompensationProviderProps> = ({ children }) => {
  const [selectedExecution, setSelectedExecution] = useState<ExecutionFailedState | null>(null);

  return (
    <CompensationContext.Provider value={{ selectedExecution, setSelectedExecution }}>
      {children}
    </CompensationContext.Provider>
  );
};
