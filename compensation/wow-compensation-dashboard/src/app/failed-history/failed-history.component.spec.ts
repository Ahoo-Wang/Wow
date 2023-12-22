import { ComponentFixture, TestBed } from '@angular/core/testing';

import { FailedHistoryComponent } from './failed-history.component';

describe('FailedHistoryComponent', () => {
  let component: FailedHistoryComponent;
  let fixture: ComponentFixture<FailedHistoryComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [FailedHistoryComponent]
    })
    .compileComponents();
    
    fixture = TestBed.createComponent(FailedHistoryComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
