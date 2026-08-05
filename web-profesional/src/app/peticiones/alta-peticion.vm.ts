import { computed, inject, Injectable, signal } from '@angular/core';

import { Catalogo, CatalogoDePruebas } from '../fhir/catalogo';
import { ClienteFhir, ErrorDelLaboratorio } from '../fhir/cliente-fhir';
import { SIN_RESPUESTA } from '../fhir/operation-outcome';
import { porIdentificador, SID_COLEGIADO, SID_DNI_NIE, SID_NHC } from '../fhir/sistemas';
import { Patient, Practitioner, ServiceRequest } from '../fhir/tipos';

/**
 * La lógica de la pantalla de alta de petición. El componente solo la orquesta.
 *
 * Reproduce lo que hace el mostrador de un laboratorio privado, en el mismo orden: se identifica al
 * paciente, se identifica a quien firma el volante, se marcan las pruebas y se registra la petición.
 * Los dos primeros pasos son <em>buscar o dar de alta</em>, porque en un laboratorio privado el
 * paciente que llega por primera vez es el caso normal, no la excepción.
 *
 * **Una petición son varias líneas que comparten número.** No se registra «una petición con pruebas
 * dentro»: se registra un `ServiceRequest` por prueba, todos con el mismo `requisition`. Es lo que
 * permite que cada una avance a su ritmo — unas se informan hoy y otras tardan tres días.
 */

/** Filiación mínima para dar de alta a un paciente que aún no consta. */
export interface FiliacionNueva {
  readonly nhc: string;
  /** El nombre familiar **completo**. Nunca se parte por el espacio. */
  readonly apellidos: string;
  readonly nombre: string;
  readonly sexo: 'male' | 'female' | 'other' | 'unknown';
  readonly fechaDeNacimiento?: string;
  readonly dniNie?: string;
}

/** Lo que hay que saber de quien firma el volante. */
export interface FacultativoNuevo {
  readonly colegiado: string;
  readonly apellidos: string;
  readonly nombre: string;
}

/** El resultado de registrar la petición, que es lo que se le enseña al paciente. */
export interface PeticionRegistrada {
  readonly numero: string;
  readonly lineas: number;
}

@Injectable()
export class AltaPeticionVm {
  private readonly cliente = inject(ClienteFhir);
  private readonly pruebasDelLaboratorio = inject(CatalogoDePruebas);

  private readonly catalogo = signal<Catalogo | undefined>(undefined);

  readonly pruebas = computed(() => this.catalogo()?.pruebas ?? []);
  readonly paciente = signal<Patient | undefined>(undefined);
  readonly facultativo = signal<Practitioner | undefined>(undefined);
  readonly seleccionadas = signal<readonly string[]>([]);
  readonly registrada = signal<PeticionRegistrada | undefined>(undefined);

  /** Hay una llamada en curso: la pantalla se bloquea para no duplicar peticiones. */
  readonly trabajando = signal(false);

  /** Lo que ha ido mal, en español y listo para enseñar. Vacío si no ha ido mal nada. */
  readonly error = signal('');

  /** Lo que hay que saber sin que sea un fallo: «ese número de historia no consta». */
  readonly aviso = signal('');

  /** Falta alguno de los tres ingredientes: paciente, facultativo o al menos una prueba. */
  readonly puedePedir = computed(
    () => !!this.paciente() && !!this.facultativo() && this.seleccionadas().length > 0,
  );

  async cargarCatalogo(): Promise<void> {
    await this.intentar(async () => this.catalogo.set(await this.pruebasDelLaboratorio.cargar()));
  }

  /** Busca al paciente por su número de historia. No encontrarlo no es un error. */
  async buscarPaciente(nhc: string): Promise<void> {
    await this.intentar(async () => {
      this.paciente.set(undefined);
      const encontrados = await this.cliente.buscar<Patient>('Patient', {
        identifier: porIdentificador(SID_NHC, nhc.trim()),
      });

      this.paciente.set(encontrados.recursos[0]);
      if (!this.paciente()) {
        this.aviso.set(`No consta ningún paciente con el número de historia ${nhc}. Regístralo.`);
      }
    });
  }

  async registrarPaciente(filiacion: FiliacionNueva): Promise<void> {
    await this.intentar(async () => {
      this.paciente.set(await this.cliente.crear(comoPatient(filiacion)));
    });
  }

  async buscarFacultativo(colegiado: string): Promise<void> {
    await this.intentar(async () => {
      this.facultativo.set(undefined);
      const encontrados = await this.cliente.buscar<Practitioner>('Practitioner', {
        identifier: porIdentificador(SID_COLEGIADO, colegiado.trim()),
      });

      this.facultativo.set(encontrados.recursos[0]);
      if (!this.facultativo()) {
        this.aviso.set(`No consta ningún facultativo con el número ${colegiado}. Regístralo.`);
      }
    });
  }

  async registrarFacultativo(datos: FacultativoNuevo): Promise<void> {
    await this.intentar(async () => {
      this.facultativo.set(await this.cliente.crear(comoPractitioner(datos)));
    });
  }

  /** Marca o desmarca una prueba del catálogo. */
  alternar(codigo: string): void {
    this.seleccionadas.update((marcadas) =>
      marcadas.includes(codigo)
        ? marcadas.filter((otra) => otra !== codigo)
        : [...marcadas, codigo],
    );
  }

  /**
   * Registra la petición: una línea por prueba marcada, todas con el mismo número.
   *
   * Las líneas se envían **de una en una y en orden**. Si una falla, se dice cuántas quedaron
   * registradas: el servidor no ofrece forma de deshacer las anteriores, y callarlo dejaría al
   * mostrador creyendo que no se pidió nada cuando sí se pidió parte.
   */
  async pedir(): Promise<void> {
    const paciente = this.paciente();
    const facultativo = this.facultativo();
    if (!paciente?.id || !facultativo?.id) {
      return;
    }

    const numero = numeroDePeticion();
    const codigos = this.seleccionadas();
    let registradas = 0;

    this.trabajando.set(true);
    this.error.set('');
    this.aviso.set('');
    try {
      for (const codigo of codigos) {
        await this.cliente.crear<ServiceRequest>(
          comoServiceRequest(numero, paciente.id, facultativo.id, codigo, this.systemDelCatalogo()),
        );
        registradas++;
      }
      this.registrada.set({ numero, lineas: registradas });
      this.seleccionadas.set([]);
    } catch (fallo) {
      this.error.set(
        registradas === 0
          ? motivoDe(fallo)
          : `${motivoDe(fallo)} La petición ${numero} quedó registrada con ${registradas} de ${codigos.length} pruebas.`,
      );
    } finally {
      this.trabajando.set(false);
    }
  }

  /** Deja la pantalla lista para la siguiente petición, sin recargar. */
  empezarDeNuevo(): void {
    this.paciente.set(undefined);
    this.facultativo.set(undefined);
    this.seleccionadas.set([]);
    this.registrada.set(undefined);
    this.error.set('');
    this.aviso.set('');
  }

  private systemDelCatalogo(): string {
    const catalogo = this.catalogo();
    if (!catalogo) {
      throw new Error('El catálogo de pruebas no se ha cargado.');
    }
    return catalogo.system;
  }

  private async intentar(paso: () => Promise<void>): Promise<void> {
    this.trabajando.set(true);
    this.error.set('');
    this.aviso.set('');
    try {
      await paso();
    } catch (fallo) {
      this.error.set(motivoDe(fallo));
    } finally {
      this.trabajando.set(false);
    }
  }
}

function motivoDe(fallo: unknown): string {
  return fallo instanceof ErrorDelLaboratorio ? fallo.message : SIN_RESPUESTA;
}

/**
 * El número que agrupa las líneas del mismo volante.
 *
 * Lo pone el cliente porque la API lo exige en el recurso, y **no es único a propósito**: es lo que
 * agrupa. Lleva la fecha por delante para que se lea, y un sufijo al azar para que dos mostradores
 * que registren a la vez no acaben mezclando dos volantes en uno.
 */
function numeroDePeticion(fecha = new Date()): string {
  const dia = [
    fecha.getFullYear(),
    String(fecha.getMonth() + 1).padStart(2, '0'),
    String(fecha.getDate()).padStart(2, '0'),
  ].join('');
  const sufijo = Math.random().toString(36).slice(2, 8).toUpperCase();
  return `P${dia}-${sufijo}`;
}

function comoPatient(filiacion: FiliacionNueva): Patient {
  const identificadores = [{ system: SID_NHC, value: filiacion.nhc.trim() }];
  if (filiacion.dniNie?.trim()) {
    identificadores.push({ system: SID_DNI_NIE, value: filiacion.dniNie.trim().toUpperCase() });
  }

  return {
    resourceType: 'Patient',
    identifier: identificadores,
    // `family` lleva el nombre familiar entero. Partirlo por el espacio para «separar los dos
    // apellidos» convierte a «de la Torre Gómez» en «de» y «la Torre Gómez».
    name: [
      { use: 'official', family: filiacion.apellidos.trim(), given: [filiacion.nombre.trim()] },
    ],
    gender: filiacion.sexo,
    birthDate: filiacion.fechaDeNacimiento || undefined,
  };
}

function comoPractitioner(datos: FacultativoNuevo): Practitioner {
  return {
    resourceType: 'Practitioner',
    identifier: [{ system: SID_COLEGIADO, value: datos.colegiado.trim() }],
    name: [{ use: 'official', family: datos.apellidos.trim(), given: [datos.nombre.trim()] }],
  };
}

function comoServiceRequest(
  numero: string,
  pacienteId: string,
  facultativoId: string,
  codigo: string,
  system: string,
): ServiceRequest {
  return {
    resourceType: 'ServiceRequest',
    status: 'active',
    intent: 'order',
    requisition: { value: numero },
    subject: { reference: `Patient/${pacienteId}` },
    requester: { reference: `Practitioner/${facultativoId}` },
    // ⚠️ R5: `code` es un `CodeableReference`, así que el concepto va dentro de `code.concept`.
    // Copiado de un ejemplo de R4 esto sería `code: { coding: [...] }`, y el servidor lo rechaza.
    code: { concept: { coding: [{ system, code: codigo }] } },
  };
}
