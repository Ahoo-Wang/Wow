import { TestBed } from '@angular/core/testing';

import { DeviceIdStoreService } from './device-id-store.service';

describe('DeviceIdStoreService', () => {
  let service: DeviceIdStoreService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(DeviceIdStoreService);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });
});
