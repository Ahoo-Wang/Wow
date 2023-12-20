import { ComponentFixture, TestBed } from '@angular/core/testing';

import { FailedListComponent } from './failed-list.component';

describe('FailedListComponent', () => {
  let component: FailedListComponent;
  let fixture: ComponentFixture<FailedListComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [FailedListComponent]
    })
    .compileComponents();
    
    fixture = TestBed.createComponent(FailedListComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
