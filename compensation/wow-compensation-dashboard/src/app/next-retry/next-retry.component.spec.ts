import { ComponentFixture, TestBed } from '@angular/core/testing';

import { NextRetryComponent } from './next-retry.component';

describe('NextRetryComponent', () => {
  let component: NextRetryComponent;
  let fixture: ComponentFixture<NextRetryComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [NextRetryComponent]
    })
    .compileComponents();
    
    fixture = TestBed.createComponent(NextRetryComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
