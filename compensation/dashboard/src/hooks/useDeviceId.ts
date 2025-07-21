import { useEffect, useState } from 'react';

const DEVICE_ID_KEY = 'deviceId';

export const useDeviceId = () => {
  const [deviceId, setDeviceId] = useState<string>('');

  useEffect(() => {
    const storedDeviceId = localStorage.getItem(DEVICE_ID_KEY);
    if (storedDeviceId) {
      setDeviceId(storedDeviceId);
    } else {
      const newDeviceId = Math.random().toString(36).substring(2, 15) + Math.random().toString(36).substring(2, 15);
      localStorage.setItem(DEVICE_ID_KEY, newDeviceId);
      setDeviceId(newDeviceId);
    }
  }, []);

  return deviceId;
};
