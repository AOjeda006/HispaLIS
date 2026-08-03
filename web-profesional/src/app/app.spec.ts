import { TestBed } from '@angular/core/testing';
import { App } from './app';

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
    }).compileComponents();
  });

  it('se crea el componente raíz', () => {
    const fixture = TestBed.createComponent(App);

    expect(fixture.componentInstance).toBeTruthy();
  });

  it('muestra el título del laboratorio', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();

    const contenido = fixture.nativeElement as HTMLElement;

    expect(contenido.querySelector('h1')?.textContent).toContain('HispaLIS');
  });

  it('advierte de que es una simulación con datos sintéticos', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();

    const contenido = fixture.nativeElement as HTMLElement;

    expect(contenido.querySelector('.aviso')?.textContent).toContain('datos sintéticos');
  });
});
