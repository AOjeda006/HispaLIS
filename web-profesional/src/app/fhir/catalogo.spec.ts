import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { CatalogoDePruebas } from './catalogo';

const CODESYSTEM = {
  resourceType: 'CodeSystem',
  url: 'https://aojeda006.github.io/HispaLIS/fhir/CodeSystem/catalogo-pruebas',
  concept: [
    {
      code: 'GLU',
      display: 'Glucosa',
      property: [{ code: 'unidad-ucum', valueCoding: { code: 'mg/dL' } }],
    },
    { code: 'LEG', display: 'Antígeno de Legionella en orina' },
  ],
};

describe('el catálogo de pruebas', () => {
  let catalogo: CatalogoDePruebas;
  let servidor: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    catalogo = TestBed.inject(CatalogoDePruebas);
    servidor = TestBed.inject(HttpTestingController);
  });

  afterEach(() => servidor.verify());

  it('son las pruebas que publica la guía, con su nombre en español', async () => {
    const cargado = catalogo.cargar();
    servidor.expectOne('terminologia/CodeSystem-catalogo-pruebas.json').flush(CODESYSTEM);

    await expect(cargado).resolves.toMatchObject({
      pruebas: [
        { codigo: 'GLU', display: 'Glucosa' },
        { codigo: 'LEG', display: 'Antígeno de Legionella en orina' },
      ],
    });
  });

  it('hasta el system sale del fichero, no de una constante escrita aquí', async () => {
    // Es el invariante D15: la web consume el mismo CodeSystem que el backend y el generador. Un
    // `system` repetido a mano es la primera pieza que se queda vieja, y con él la búsqueda deja de
    // encontrar sin que falle nada.
    const cargado = catalogo.cargar();
    servidor.expectOne('terminologia/CodeSystem-catalogo-pruebas.json').flush(CODESYSTEM);

    await expect(cargado).resolves.toMatchObject({ system: CODESYSTEM.url });
  });

  it('se pide una vez por sesión: es terminología, no cambia mientras se trabaja', async () => {
    const primera = catalogo.cargar();
    servidor.expectOne('terminologia/CodeSystem-catalogo-pruebas.json').flush(CODESYSTEM);
    await primera;

    await catalogo.cargar();

    // `verify()` en el afterEach falla si hubiese quedado una segunda petición sin atender.
    servidor.expectNone('terminologia/CodeSystem-catalogo-pruebas.json');
  });
});
