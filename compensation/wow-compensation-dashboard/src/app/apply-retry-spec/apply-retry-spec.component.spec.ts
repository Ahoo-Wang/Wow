import { ComponentFixture, TestBed } from '@angular/core/testing';

import { ApplyRetrySpecComponent } from './apply-retry-spec.component';

describe('ApplyRetrySpecComponent', () => {
  let component: ApplyRetrySpecComponent;
  let fixture: ComponentFixture<ApplyRetrySpecComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ApplyRetrySpecComponent]
    })
    .compileComponents();
    
    fixture = TestBed.createComponent(ApplyRetrySpecComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
