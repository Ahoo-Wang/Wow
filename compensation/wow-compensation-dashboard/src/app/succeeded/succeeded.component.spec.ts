import { ComponentFixture, TestBed } from '@angular/core/testing';

import { SucceededComponent } from './succeeded.component';

describe('SucceededComponent', () => {
  let component: SucceededComponent;
  let fixture: ComponentFixture<SucceededComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SucceededComponent]
    })
    .compileComponents();
    
    fixture = TestBed.createComponent(SucceededComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
