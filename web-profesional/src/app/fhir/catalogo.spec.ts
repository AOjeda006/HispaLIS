import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { CatalogoDePruebas } from './catalogo';

const SYSTEM = 'https://aojeda006.github.io/HispaLIS/fhir/CodeSystem/catalogo-pruebas';
const VALUE_SET = 'https://aojeda006.github.io/HispaLIS/fhir/ValueSet/pruebas-del-catalogo';

const EXPANSION = {
  resourceType: 'ValueSet',
  url: VALUE_SET,
  expansion: {
    total: 2,
    contains: [
      { system: SYSTEM, code: 'GLU', display: 'Glucosa' },
      { system: SYSTEM, code: 'LEG', display: 'Antígeno de Legionella en orina' },
    ],
  },
};

/**
 * La petición que la web hace al servidor de terminología, con sus dos parámetros.
 *
 * Sin `encodeURIComponent`: el codificador de `HttpParams` deja `:` y `/` tal cual —son legales en
 * una cadena de consulta— y `urlWithParams`, que es contra lo que casa el doble del cliente HTTP,
 * lleva la URL canónica sin escapar.
 */
const PETICION = `/terminologia/ValueSet/$expand?url=${VALUE_SET}&count=500`;

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

  it('se pregunta al servidor de terminología con $expand, no se lee de un fichero del paquete', async () => {
    const cargado = catalogo.cargar();

    const peticion = servidor.expectOne((r) => r.url === '/terminologia/ValueSet/$expand');
    // La URL canónica identifica el conjunto; el servidor decide dónde está. Es lo que permite
    // cambiar de servidor de terminología sin tocar el cliente (D14).
    expect(peticion.request.params.get('url')).toBe(VALUE_SET);
    peticion.flush(EXPANSION);

    await expect(cargado).resolves.toMatchObject({
      pruebas: [
        { codigo: 'GLU', display: 'Glucosa' },
        { codigo: 'LEG', display: 'Antígeno de Legionella en orina' },
      ],
    });
  });

  it('pide `count` explícito, para que un catálogo grande no se corte en silencio', async () => {
    const cargado = catalogo.cargar();

    const peticion = servidor.expectOne((r) => r.url === '/terminologia/ValueSet/$expand');
    expect(peticion.request.params.get('count')).toBe('500');
    peticion.flush(EXPANSION);

    await cargado;
  });

  it('hasta el system sale de la expansión, no de una constante escrita aquí', async () => {
    // Es el invariante D15: la web consume el mismo catálogo que el backend y el generador. Un
    // `system` repetido a mano es la primera pieza que se queda vieja, y con él la búsqueda deja de
    // encontrar sin que falle nada.
    const cargado = catalogo.cargar();
    servidor.expectOne(PETICION).flush(EXPANSION);

    await expect(cargado).resolves.toMatchObject({ system: SYSTEM });
  });

  it('se pide una vez por sesión: es terminología, no cambia mientras se trabaja', async () => {
    const primera = catalogo.cargar();
    servidor.expectOne(PETICION).flush(EXPANSION);
    await primera;

    await catalogo.cargar();

    // `verify()` en el afterEach falla si hubiese quedado una segunda petición sin atender.
    servidor.expectNone(PETICION);
  });

  it('una expansión vacía deja el catálogo vacío, no revienta la pantalla de alta', async () => {
    const cargado = catalogo.cargar();
    servidor
      .expectOne(PETICION)
      .flush({ resourceType: 'ValueSet', expansion: { total: 0, contains: [] } });

    await expect(cargado).resolves.toMatchObject({ system: '', pruebas: [] });
  });
});
