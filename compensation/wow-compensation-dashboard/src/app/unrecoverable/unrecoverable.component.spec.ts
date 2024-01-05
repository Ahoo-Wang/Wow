import { ComponentFixture, TestBed } from '@angular/core/testing';

import { UnrecoverableComponent } from './unrecoverable.component';

describe('UnrecoverableComponent', () => {
  let component: UnrecoverableComponent;
  let fixture: ComponentFixture<UnrecoverableComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [UnrecoverableComponent]
    })
    .compileComponents();
    
    fixture = TestBed.createComponent(UnrecoverableComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
