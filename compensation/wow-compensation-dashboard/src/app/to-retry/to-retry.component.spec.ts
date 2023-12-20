import { ComponentFixture, TestBed } from '@angular/core/testing';

import { ToRetryComponent } from './to-retry.component';

describe('ToRetryComponent', () => {
  let component: ToRetryComponent;
  let fixture: ComponentFixture<ToRetryComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ToRetryComponent]
    })
    .compileComponents();
    
    fixture = TestBed.createComponent(ToRetryComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
