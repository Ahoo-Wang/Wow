import { ComponentFixture, TestBed } from '@angular/core/testing';

import { NonRetryableComponent } from './non-retryable.component';

describe('NonRetryableComponent', () => {
  let component: NonRetryableComponent;
  let fixture: ComponentFixture<NonRetryableComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [NonRetryableComponent]
    })
    .compileComponents();
    
    fixture = TestBed.createComponent(NonRetryableComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
