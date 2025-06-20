import {Injectable} from '@angular/core';
import {v4 as uuidv4} from 'uuid';

export const CoSecDeviceIdKey = 'CoSec-Device-Id';

@Injectable({
  providedIn: 'root'
})
export class DeviceIdStoreService {

  constructor() {
  }

  getDeviceId(): string {
    const deviceId = localStorage.getItem(CoSecDeviceIdKey);
    if (deviceId) {
      return deviceId;
    }
    const newDeviceId = uuidv4();
    localStorage.setItem(CoSecDeviceIdKey, newDeviceId);
    return newDeviceId;
  }
}
