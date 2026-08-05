/**
 * Los tipos de FHIR **R5** que esta web usa, y solo esos.
 *
 * Se escriben a mano en vez de generarlos del paquete canónico porque la web toca cinco recursos y
 * un puñado de elementos de cada uno: un modelo completo de R5 son miles de líneas de tipos que
 * nadie lee y que hacen más difícil ver qué se usa de verdad. Lo que sí se respeta al milímetro es
 * la forma de R5.
 *
 * ⚠️ **R5 no es R4.** Aquí muerde en un sitio concreto: `ServiceRequest.code` es un
 * `CodeableReference`, así que el concepto va **dentro de `code.concept`** y no directamente en
 * `code`. Copiar un ejemplo de R4 produce un recurso que el servidor rechaza.
 */

export interface Coding {
  readonly system?: string;
  readonly code?: string;
  readonly display?: string;
}

export interface CodeableConcept {
  readonly coding?: readonly Coding[];
  readonly text?: string;
}

/** ⚠️ R5: envoltorio de `ServiceRequest.code`, que en R4 era un `CodeableConcept` a secas. */
export interface CodeableReference {
  readonly concept?: CodeableConcept;
  readonly reference?: Referencia;
}

export interface Referencia {
  readonly reference?: string;
  readonly display?: string;
}

export interface Identificador {
  readonly system?: string;
  readonly value?: string;
}

export interface Cantidad {
  readonly value?: number;
  /** Lo que se imprime en el informe. */
  readonly unit?: string;
  readonly system?: string;
  /** El código UCUM, que es lo que permite convertir y comparar. */
  readonly code?: string;
}

/**
 * Una extensión de FHIR. Aquí hacen falta para los apellidos: `family` lleva el nombre familiar
 * completo y estas dos lo descomponen sin que nadie tenga que partir por el espacio.
 */
export interface Extension {
  readonly url: string;
  readonly valueString?: string;
  readonly valueCode?: string;
  readonly extension?: readonly Extension[];
}

export interface HumanName {
  readonly use?: string;
  /** El nombre familiar **completo**. Nunca se parte por el espacio. */
  readonly family?: string;
  /** Elemento primitivo hermano de `family`, donde viven sus extensiones. */
  readonly _family?: { readonly extension?: readonly Extension[] };
  readonly given?: readonly string[];
}

export interface Patient {
  readonly resourceType: 'Patient';
  readonly id?: string;
  readonly identifier?: readonly Identificador[];
  readonly name?: readonly HumanName[];
  readonly gender?: 'male' | 'female' | 'other' | 'unknown';
  readonly birthDate?: string;
}

export interface ServiceRequest {
  readonly resourceType: 'ServiceRequest';
  readonly id?: string;
  readonly status: string;
  readonly intent: string;
  /** ⚠️ R5: `CodeableReference`, no `CodeableConcept`. */
  readonly code?: CodeableReference;
  readonly requisition?: Identificador;
  readonly subject: Referencia;
  readonly specimen?: readonly Referencia[];
}

export interface RangoDeReferencia {
  readonly low?: Cantidad;
  readonly high?: Cantidad;
  /** A qué población aplica el rango; aquí, el sexo, en SNOMED. */
  readonly appliesTo?: readonly CodeableConcept[];
}

export interface Observation {
  readonly resourceType: 'Observation';
  readonly id?: string;
  readonly status: string;
  readonly code: CodeableConcept;
  readonly subject?: Referencia;
  readonly specimen?: Referencia;
  readonly effectiveDateTime?: string;
  readonly issued?: string;
  readonly performer?: readonly Referencia[];
  readonly valueQuantity?: Cantidad;
  readonly valueString?: string;
  readonly valueCodeableConcept?: CodeableConcept;
  readonly referenceRange?: readonly RangoDeReferencia[];
}

export interface DiagnosticReport {
  readonly resourceType: 'DiagnosticReport';
  readonly id?: string;
  readonly status: string;
  readonly code?: CodeableConcept;
  readonly subject?: Referencia;
  readonly result?: readonly Referencia[];
  readonly effectiveDateTime?: string;
  readonly issued?: string;
  readonly conclusion?: string;
}

export interface EnlaceDeBundle {
  readonly relation: string;
  readonly url: string;
}

export interface EntradaDeBundle<T> {
  readonly fullUrl?: string;
  readonly resource?: T;
}

export interface Bundle<T> {
  readonly resourceType: 'Bundle';
  readonly type: string;
  readonly total?: number;
  readonly link?: readonly EnlaceDeBundle[];
  readonly entry?: readonly EntradaDeBundle<T>[];
}

export interface IncidenciaDeOperationOutcome {
  readonly severity: 'fatal' | 'error' | 'warning' | 'information' | 'success';
  readonly code: string;
  readonly diagnostics?: string;
  readonly details?: CodeableConcept;
}

export interface OperationOutcome {
  readonly resourceType: 'OperationOutcome';
  readonly issue: readonly IncidenciaDeOperationOutcome[];
}

/** Sexo al que aplica un rango de referencia, tal y como lo codifica el laboratorio. */
export const SEXO_EN_SNOMED: Readonly<Record<string, string>> = {
  male: '248153007',
  female: '248152002',
};
