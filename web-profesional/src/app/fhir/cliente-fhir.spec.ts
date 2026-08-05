import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { ClienteFhir, ErrorDelLaboratorio } from './cliente-fhir';
import { SIN_RESPUESTA } from './operation-outcome';
import { SID_NHC } from './sistemas';
import { Bundle, Patient } from './tipos';

/** Deja que se resuelvan las promesas pendientes antes de mirar la petición siguiente. */
function cedeElTurno(): Promise<void> {
  return new Promise((sigue) => setTimeout(sigue, 0));
}

const PACIENTE: Patient = {
  resourceType: 'Patient',
  id: 'a1',
  name: [{ family: 'Peña Muñoz', given: ['Begoña'] }],
};

function bundle(recursos: Patient[], siguiente?: string): Bundle<Patient> {
  return {
    resourceType: 'Bundle',
    type: 'searchset',
    total: recursos.length,
    link: siguiente ? [{ relation: 'next', url: siguiente }] : [],
    entry: recursos.map((recurso) => ({ resource: recurso })),
  };
}

describe('el cliente de la API FHIR', () => {
  let cliente: ClienteFhir;
  let servidor: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    cliente = TestBed.inject(ClienteFhir);
    servidor = TestBed.inject(HttpTestingController);
  });

  afterEach(() => servidor.verify());

  it('lee un recurso por su referencia', async () => {
    const leido = cliente.leer<Patient>('Patient/a1');

    const peticion = servidor.expectOne('/fhir/Patient/a1');
    expect(peticion.request.headers.get('Accept')).toBe('application/fhir+json');
    peticion.flush(PACIENTE);

    await expect(leido).resolves.toEqual(PACIENTE);
  });

  it('al dar de alta devuelve lo que publicó el servidor, no lo que se le mandó', async () => {
    // El servidor asigna el id y puede normalizar lo recibido. Devolver el objeto enviado haría
    // creer que se guardó tal cual, y el id que hace falta para el paso siguiente no estaría.
    const enviado: Patient = { resourceType: 'Patient', name: [{ family: 'Peña Muñoz' }] };

    const creado = cliente.crear(enviado);

    const peticion = servidor.expectOne('/fhir/Patient');
    expect(peticion.request.method).toBe('POST');
    expect(peticion.request.headers.get('Content-Type')).toBe('application/fhir+json');
    peticion.flush(PACIENTE, { status: 201, statusText: 'Created' });

    await expect(creado).resolves.toEqual(PACIENTE);
  });

  it('si el alta no devuelve cuerpo, lo lee del Location', async () => {
    const creado = cliente.crear<Patient>({ resourceType: 'Patient' });

    servidor.expectOne('/fhir/Patient').flush(null, {
      status: 201,
      statusText: 'Created',
      headers: { Location: 'http://laboratorio.example/fhir/Patient/a1/_history/1' },
    });
    await cedeElTurno();

    // Y ese segundo viaje es el read-your-writes del §9 de verdad: si la proyección no estuviera
    // escrita ya, esto daría 404.
    servidor.expectOne('/fhir/Patient/a1').flush(PACIENTE);

    await expect(creado).resolves.toEqual(PACIENTE);
  });

  it('busca sin escribir el número de historia en la URL', async () => {
    // Una URL con el NHC dentro se queda en la barra del navegador, en su historial, en el log del
    // proxy y en la traza del servidor. FHIR admite los mismos criterios en el cuerpo, y es lo que
    // exige el invariante del proyecto: nunca PHI en URLs.
    const nhc = '00000042';
    const busqueda = cliente.buscar<Patient>('Patient', { identifier: `${SID_NHC}|${nhc}` });

    const peticion = servidor.expectOne('/fhir/Patient/_search');
    expect(peticion.request.method).toBe('POST');
    expect(peticion.request.urlWithParams).not.toContain(nhc);
    expect(peticion.request.headers.get('Content-Type')).toBe('application/x-www-form-urlencoded');
    expect(peticion.request.body).toContain('identifier=');
    expect(peticion.request.body).toContain(nhc);
    peticion.flush(bundle([PACIENTE]));

    await expect(busqueda).resolves.toMatchObject({ recursos: [PACIENTE], total: 1 });
  });

  it('pide la página siguiente con la URL que dio el servidor, sin tocarla', async () => {
    // La URL es opaca: lleva el identificador de la búsqueda cacheada, no un desplazamiento que el
    // cliente pueda calcular. Inventársela funciona hasta que el servidor cambia de estrategia, y
    // entonces se salta resultados en silencio.
    const opaca = 'http://laboratorio.example/fhir?_getpages=7c3f&_getpagesoffset=5';
    const primera = cliente.buscar<Patient>('Patient', { _count: 5 });
    servidor.expectOne('/fhir/Patient/_search').flush(bundle([PACIENTE], opaca));

    const segunda = cliente.siguiente(await primera);

    servidor.expectOne(opaca).flush(bundle([PACIENTE]));
    await expect(segunda).resolves.toMatchObject({ siguiente: undefined });
  });

  it('recorre todas las páginas hasta que el servidor deja de dar enlace', async () => {
    const segunda = 'http://laboratorio.example/fhir?_getpages=7c3f&_getpagesoffset=1';
    const todos = cliente.todo<Patient>('Patient', {});

    servidor.expectOne('/fhir/Patient/_search').flush(bundle([PACIENTE], segunda));
    await cedeElTurno();
    servidor.expectOne(segunda).flush(bundle([{ ...PACIENTE, id: 'a2' }]));

    await expect(todos).resolves.toHaveLength(2);
  });

  it('traduce el fallo del servidor al motivo que este dio', async () => {
    const fallo = cliente.crear<Patient>({ resourceType: 'Patient' });

    servidor.expectOne('/fhir/Patient').flush(
      {
        resourceType: 'OperationOutcome',
        issue: [
          {
            severity: 'error',
            code: 'business-rule',
            diagnostics: 'El número de historia clínica son ocho dígitos',
          },
        ],
      },
      { status: 422, statusText: 'Unprocessable Entity' },
    );

    await expect(fallo).rejects.toThrow(ErrorDelLaboratorio);
    await expect(fallo).rejects.toThrow('El número de historia clínica son ocho dígitos.');
  });

  it('conserva el código HTTP, que es lo que distingue reintentar de corregir', async () => {
    // Un 412 se arregla releyendo y reintentando; un 422 no se arregla reintentando nunca.
    const fallo = cliente.leer<Patient>('Patient/a1');
    servidor
      .expectOne('/fhir/Patient/a1')
      .flush({ resourceType: 'OperationOutcome', issue: [] }, { status: 412, statusText: '' });

    await expect(fallo).rejects.toMatchObject({ estado: 412 });
  });

  it('cuando no hay respuesta lo dice, en vez de callarse', async () => {
    const fallo = cliente.leer<Patient>('Patient/a1');
    servidor.expectOne('/fhir/Patient/a1').error(new ProgressEvent('error'));

    await expect(fallo).rejects.toThrow(SIN_RESPUESTA);
  });
});
