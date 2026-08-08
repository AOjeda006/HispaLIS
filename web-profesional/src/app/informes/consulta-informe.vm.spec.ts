import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { Bundle, DiagnosticReport, Observation, Patient } from '../fhir/tipos';
import { ConsultaInformeVm } from './consulta-informe.vm';

const SYSTEM = 'https://aojeda006.github.io/HispaLIS/fhir/CodeSystem/catalogo-pruebas';

const EXPANSION = {
  resourceType: 'ValueSet',
  expansion: {
    contains: [{ system: SYSTEM, code: 'HB', display: 'Hemoglobina' }],
  },
};

const PACIENTE: Patient = {
  resourceType: 'Patient',
  id: 'p1',
  gender: 'female',
  identifier: [{ system: 'https://aojeda006.github.io/HispaLIS/sid/nhc', value: '00000042' }],
  name: [{ family: 'Peña Muñoz', given: ['Begoña'] }],
};

const INFORME: DiagnosticReport = {
  resourceType: 'DiagnosticReport',
  id: 'i1',
  status: 'final',
  issued: '2026-08-04T08:15:00Z',
  result: [{ reference: 'Observation/o1' }],
};

const HEMOGLOBINA: Observation = {
  resourceType: 'Observation',
  id: 'o1',
  status: 'final',
  // La proyección publica el código del catálogo **sin display**: el término lo resuelve quien
  // tiene la terminología. Si el cliente no lo resuelve, el informe dice «HB».
  code: { coding: [{ system: SYSTEM, code: 'HB' }] },
  valueQuantity: { value: 13, unit: 'g/dL' },
  referenceRange: [
    {
      low: { value: 12, unit: 'g/dL' },
      high: { value: 16, unit: 'g/dL' },
      appliesTo: [{ coding: [{ system: 'http://snomed.info/sct', code: '248152002' }] }],
    },
  ],
};

function bundle<T>(recursos: T[], siguiente?: string): Bundle<T> {
  return {
    resourceType: 'Bundle',
    type: 'searchset',
    total: recursos.length,
    link: siguiente ? [{ relation: 'next', url: siguiente }] : [],
    entry: recursos.map((recurso) => ({ resource: recurso })),
  };
}

function cedeElTurno(): Promise<void> {
  return new Promise((sigue) => setTimeout(sigue, 0));
}

describe('la consulta de informe', () => {
  let vm: ConsultaInformeVm;
  let servidor: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), ConsultaInformeVm],
    });
    vm = TestBed.inject(ConsultaInformeVm);
    servidor = TestBed.inject(HttpTestingController);
  });

  afterEach(() => servidor.verify());

  /** Busca al paciente y devuelve la petición de búsqueda de informes, sin responderla. */
  async function buscar(siguiente?: string) {
    const buscado = vm.buscar('00000042');
    servidor.expectOne('/fhir/Patient/_search').flush(bundle([PACIENTE]));
    await cedeElTurno();

    const informes = servidor.expectOne('/fhir/DiagnosticReport/_search');
    informes.flush(bundle([INFORME], siguiente));
    await buscado;
    return informes;
  }

  it('lista los informes del paciente, del más reciente al más antiguo', async () => {
    const informes = await buscar();

    // Sin `_sort`, el más nuevo puede quedar en la última página; que es justo el que se consulta.
    expect(informes.request.body).toContain('_sort=-issued');
    expect(informes.request.body).toContain('patient=Patient/p1');
    expect(vm.informes()).toHaveLength(1);
    expect(vm.total()).toBe(1);
  });

  it('«ver más» sigue el enlace del servidor y añade lo que traiga', async () => {
    const opaca = 'http://laboratorio.example/fhir?_getpages=7c3f&_getpagesoffset=10';
    await buscar(opaca);
    expect(vm.hayMas()).toBe(true);

    const mas = vm.verMas();
    servidor.expectOne(opaca).flush(bundle([{ ...INFORME, id: 'i2' }]));
    await mas;

    expect(vm.informes().map((informe) => informe.id)).toEqual(['i1', 'i2']);
    expect(vm.hayMas()).toBe(false);
  });

  it('abrir un informe trae los resultados a los que apunta', async () => {
    await buscar();

    const abierto = vm.abrir(INFORME);
    servidor.expectOne('/fhir/Observation/o1').flush(HEMOGLOBINA);
    await abierto;

    expect(vm.resultados()).toHaveLength(1);
    expect(vm.abierto()?.id).toBe('i1');
  });

  it('el nombre de la prueba sale del catálogo, no del recurso', async () => {
    // El recurso trae `HB` y nada más, a propósito: el `display` de terminología lo resuelve quien
    // la tiene. Sin esto el informe se leería «HB 13 g/dL».
    const catalogo = vm.cargarCatalogo();
    servidor.expectOne((r) => r.url === '/terminologia/ValueSet/$expand').flush(EXPANSION);
    await catalogo;

    expect(vm.nombreDePrueba(HEMOGLOBINA)).toBe('Hemoglobina');
  });

  it('una prueba que el catálogo no conoce se enseña por su código, no en blanco', async () => {
    const catalogo = vm.cargarCatalogo();
    servidor.expectOne((r) => r.url === '/terminologia/ValueSet/$expand').flush(EXPANSION);
    await catalogo;

    const rara: Observation = {
      ...HEMOGLOBINA,
      code: { coding: [{ system: SYSTEM, code: 'ZZZ' }] },
    };

    expect(vm.nombreDePrueba(rara)).toBe('ZZZ');
  });

  it('el sexo del paciente queda disponible: es lo que decide el rango que se enseña', async () => {
    await buscar();

    expect(vm.sexo()).toBe('female');
  });

  it('un paciente sin informes lo dice, en vez de dejar la lista vacía sin explicación', async () => {
    const buscado = vm.buscar('00000042');
    servidor.expectOne('/fhir/Patient/_search').flush(bundle([PACIENTE]));
    await cedeElTurno();
    servidor.expectOne('/fhir/DiagnosticReport/_search').flush(bundle([]));
    await buscado;

    expect(vm.aviso()).toContain('todavía no tiene ningún informe');
  });
});
