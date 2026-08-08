import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { Bundle, Patient, Practitioner, ServiceRequest } from '../fhir/tipos';
import { AltaPeticionVm } from './alta-peticion.vm';

const SYSTEM = 'https://aojeda006.github.io/HispaLIS/fhir/CodeSystem/catalogo-pruebas';

const EXPANSION = {
  resourceType: 'ValueSet',
  expansion: {
    contains: [
      { system: SYSTEM, code: 'GLU', display: 'Glucosa' },
      { system: SYSTEM, code: 'TSH', display: 'Tirotropina' },
    ],
  },
};

const PACIENTE: Patient = {
  resourceType: 'Patient',
  id: 'p1',
  identifier: [{ system: 'https://aojeda006.github.io/HispaLIS/sid/nhc', value: '00000042' }],
  name: [{ family: 'Muñoz Álvarez', given: ['Begoña'] }],
};

const FACULTATIVO: Practitioner = {
  resourceType: 'Practitioner',
  id: 'f1',
  name: [{ family: 'Peña Ruiz', given: ['Ana'] }],
};

function vacio(): Bundle<never> {
  return { resourceType: 'Bundle', type: 'searchset', total: 0, entry: [] };
}

function conUno<T>(recurso: T): Bundle<T> {
  return { resourceType: 'Bundle', type: 'searchset', total: 1, entry: [{ resource: recurso }] };
}

function cedeElTurno(): Promise<void> {
  return new Promise((sigue) => setTimeout(sigue, 0));
}

describe('el alta de petición', () => {
  let vm: AltaPeticionVm;
  let servidor: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), AltaPeticionVm],
    });
    vm = TestBed.inject(AltaPeticionVm);
    servidor = TestBed.inject(HttpTestingController);
  });

  afterEach(() => servidor.verify());

  /** Deja la pantalla como estaría tras identificar al paciente y al facultativo. */
  async function conTodoIdentificado(): Promise<void> {
    const catalogo = vm.cargarCatalogo();
    servidor.expectOne((r) => r.url === '/terminologia/ValueSet/$expand').flush(EXPANSION);
    await catalogo;

    const paciente = vm.buscarPaciente('00000042');
    servidor.expectOne('/fhir/Patient/_search').flush(conUno(PACIENTE));
    await paciente;

    const facultativo = vm.buscarFacultativo('4141234');
    servidor.expectOne('/fhir/Practitioner/_search').flush(conUno(FACULTATIVO));
    await facultativo;
  }

  it('registra una línea por prueba, todas con el mismo número de petición', async () => {
    // «La petición» del volante son varias líneas que comparten número: es lo que permite que unas
    // se informen hoy y otras tarden tres días.
    await conTodoIdentificado();
    vm.alternar('GLU');
    vm.alternar('TSH');

    const pedida = vm.pedir();

    const primera = servidor.expectOne('/fhir/ServiceRequest');
    primera.flush(
      { ...(primera.request.body as ServiceRequest), id: 's1' },
      { status: 201, statusText: 'Created' },
    );
    await cedeElTurno();
    const segunda = servidor.expectOne('/fhir/ServiceRequest');
    segunda.flush(
      { ...(segunda.request.body as ServiceRequest), id: 's2' },
      { status: 201, statusText: 'Created' },
    );
    await pedida;

    const enviadas = [primera, segunda].map((peticion) => peticion.request.body as ServiceRequest);
    expect(enviadas.map((linea) => linea.requisition?.value)).toEqual([
      vm.registrada()?.numero,
      vm.registrada()?.numero,
    ]);
    expect(vm.registrada()?.lineas).toBe(2);
  });

  it('la prueba viaja en code.concept, que es como la pide R5', async () => {
    // En R4 `ServiceRequest.code` era un `CodeableConcept` a secas. Copiar aquel ejemplo produce un
    // recurso que el servidor rechaza, y el error no menciona la versión por ninguna parte.
    await conTodoIdentificado();
    vm.alternar('GLU');

    const pedida = vm.pedir();
    const enviada = servidor.expectOne('/fhir/ServiceRequest');
    enviada.flush(
      { ...(enviada.request.body as ServiceRequest), id: 's1' },
      { status: 201, statusText: 'Created' },
    );
    await pedida;

    const linea = enviada.request.body as ServiceRequest;
    expect(linea.code?.concept?.coding?.[0]).toEqual({ system: SYSTEM, code: 'GLU' });
    expect(linea.requester?.reference).toBe('Practitioner/f1');
    expect(linea.subject.reference).toBe('Patient/p1');
  });

  it('si una línea falla, dice cuántas quedaron registradas', async () => {
    // Callarlo dejaría al mostrador creyendo que no se pidió nada cuando sí se pidió parte, y el
    // servidor no ofrece forma de deshacer las anteriores.
    await conTodoIdentificado();
    vm.alternar('GLU');
    vm.alternar('TSH');

    const pedida = vm.pedir();
    const primera = servidor.expectOne('/fhir/ServiceRequest');
    primera.flush(
      { ...(primera.request.body as ServiceRequest), id: 's1' },
      { status: 201, statusText: 'Created' },
    );
    await cedeElTurno();
    servidor.expectOne('/fhir/ServiceRequest').flush(
      {
        resourceType: 'OperationOutcome',
        issue: [{ severity: 'error', code: 'invalid', diagnostics: 'TSH no está en el catálogo' }],
      },
      { status: 422, statusText: 'Unprocessable Entity' },
    );
    await pedida;

    expect(vm.error()).toContain('TSH no está en el catálogo');
    expect(vm.error()).toContain('1 de 2');
    expect(vm.registrada()).toBeUndefined();
  });

  it('que el número de historia no conste es un aviso, no un error', async () => {
    // El paciente que llega por primera vez es el caso normal en un laboratorio privado.
    const buscado = vm.buscarPaciente('00000099');
    servidor.expectOne('/fhir/Patient/_search').flush(vacio());
    await buscado;

    expect(vm.error()).toBe('');
    expect(vm.aviso()).toContain('00000099');
    expect(vm.paciente()).toBeUndefined();
  });

  it('no se puede pedir sin paciente, sin facultativo o sin pruebas', async () => {
    await conTodoIdentificado();
    expect(vm.puedePedir()).toBe(false);

    vm.alternar('GLU');
    expect(vm.puedePedir()).toBe(true);

    vm.alternar('GLU');
    expect(vm.puedePedir()).toBe(false);
  });

  it('el apellido compuesto se manda entero en family', async () => {
    // Partirlo por el espacio para «separar los dos apellidos» convierte a «de la Torre Gómez» en
    // «de» y «la Torre Gómez», y con eso el paciente ya es otro.
    const alta = vm.registrarPaciente({
      nhc: '00000043',
      apellidos: 'de la Torre Gómez',
      nombre: 'Íñigo',
      sexo: 'male',
    });

    const enviado = servidor.expectOne('/fhir/Patient');
    const recurso = enviado.request.body as Patient;
    enviado.flush({ ...recurso, id: 'p2' }, { status: 201, statusText: 'Created' });
    await alta;

    expect(recurso.name?.[0]?.family).toBe('de la Torre Gómez');
    expect(vm.paciente()?.id).toBe('p2');
  });
});
