import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { interceptorDeTestigo } from './interceptor-testigo';
import { Sesion } from './sesion';

describe('el interceptor que firma las peticiones', () => {
  let http: HttpClient;
  let servidor: HttpTestingController;
  let sesion: Sesion;

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([interceptorDeTestigo])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    servidor = TestBed.inject(HttpTestingController);
    sesion = TestBed.inject(Sesion);
    sesion.abrir({ testigo: 'testigo-vivo', caducaEn: Date.now() + 60_000, scopes: 'user/*.rs' });
  });

  afterEach(() => servidor.verify());

  it('pone el testigo en las llamadas a la API del laboratorio', () => {
    http.get('/fhir/Patient/a1').subscribe();

    const peticion = servidor.expectOne('/fhir/Patient/a1');
    expect(peticion.request.headers.get('Authorization')).toBe('Bearer testigo-vivo');
    peticion.flush({});
  });

  it('firma también la URL absoluta de la página siguiente', () => {
    // `Bundle.link[relation=next]` viene absoluta y del mismo origen. Sin esto, la primera página de
    // una búsqueda funciona y la segunda contesta 401.
    const siguiente = `${globalThis.location.origin}/fhir/Patient?_getpages=abc`;
    http.get(siguiente).subscribe();

    const peticion = servidor.expectOne(siguiente);
    expect(peticion.request.headers.get('Authorization')).toBe('Bearer testigo-vivo');
    peticion.flush({});
  });

  it('NO manda el testigo a ninguna otra URL', () => {
    // Un testigo enviado a quien no le corresponde es un testigo entregado. El canje del código va
    // al servidor de identidad y la terminología a otro servidor: ninguno de los dos es el
    // laboratorio, y ninguno de los dos tiene por qué ver el testigo del laboratorio.
    http.post('https://identidad.pruebas/token', 'grant_type=authorization_code').subscribe();
    http.get('/terminologia/ValueSet/$expand').subscribe();

    expect(
      servidor.expectOne('https://identidad.pruebas/token').request.headers.has('Authorization'),
    ).toBe(false);
    expect(
      servidor.expectOne('/terminologia/ValueSet/$expand').request.headers.has('Authorization'),
    ).toBe(false);
    servidor.match(() => true).forEach((peticion) => peticion.flush({}));
  });

  it('sin sesión, la petición sale sin cabecera y el laboratorio contesta lo que tenga que contestar', () => {
    sesion.cerrar();

    http.get('/fhir/Patient/a1').subscribe({ error: () => undefined });

    const peticion = servidor.expectOne('/fhir/Patient/a1');
    expect(peticion.request.headers.has('Authorization')).toBe(false);
    peticion.flush({}, { status: 401, statusText: 'Unauthorized' });
  });

  it('con la sesión caducada tampoco firma: un testigo muerto no es mejor que ninguno', () => {
    sesion.abrir({ testigo: 'testigo-muerto', caducaEn: Date.now() - 1, scopes: '' });

    http.get('/fhir/Patient/a1').subscribe({ error: () => undefined });

    const peticion = servidor.expectOne('/fhir/Patient/a1');
    expect(peticion.request.headers.has('Authorization')).toBe(false);
    peticion.flush({}, { status: 401, statusText: 'Unauthorized' });
  });
});
