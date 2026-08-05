import { describe, expect, it } from 'vitest';

import {
  apellidos,
  apellidosPorSeparado,
  fechaLegible,
  identificador,
  nombreCompleto,
  rangoDeReferencia,
  SIN_VALOR,
  valorConUnidad,
} from './presentacion';
import { Observation, Patient } from './tipos';

const APELLIDO_PADRE = 'http://hl7.org/fhir/StructureDefinition/humanname-fathers-family';
const APELLIDO_MADRE = 'http://hl7.org/fhir/StructureDefinition/humanname-mothers-family';
const SNOMED = 'http://snomed.info/sct';

function paciente(familia: string, padre?: string, madre?: string): Patient {
  return {
    resourceType: 'Patient',
    name: [
      {
        family: familia,
        given: ['Begoña'],
        _family:
          padre || madre
            ? {
                extension: [
                  { url: APELLIDO_PADRE, valueString: padre ?? '' },
                  { url: APELLIDO_MADRE, valueString: madre ?? '' },
                ],
              }
            : undefined,
      },
    ],
  };
}

describe('los apellidos', () => {
  it.each(['Muñoz Álvarez', 'Peña Muñoz', 'Álvarez Peña'])(
    'se muestran enteros y con su charset: %s',
    (familia) => {
      expect(apellidos(paciente(familia))).toBe(familia);
    },
  );

  it('no se parten por el espacio', () => {
    // El error habitual es cortar por el primer espacio «para separar los dos apellidos». Con este
    // paciente eso da «de» y «la Torre Gómez», y convierte a una persona en otra.
    expect(apellidos(paciente('de la Torre Gómez'))).toBe('de la Torre Gómez');
    expect(apellidos(paciente('Fernández de Córdoba Ruiz'))).toBe('Fernández de Córdoba Ruiz');
  });

  it('se descomponen con las extensiones y nunca adivinándolas', () => {
    const separados = apellidosPorSeparado(paciente('de la Torre Gómez', 'de la Torre', 'Gómez'));

    expect(separados.padre).toBe('de la Torre');
    expect(separados.madre).toBe('Gómez');
  });

  it('no se inventan cuando el recurso no trae las extensiones', () => {
    // Que no consten es información. Partir `family` para rellenarlas produciría un dato falso con
    // apariencia de bueno.
    expect(apellidosPorSeparado(paciente('de la Torre Gómez'))).toEqual({
      padre: undefined,
      madre: undefined,
    });
  });

  it('el nombre completo lleva el de pila delante', () => {
    expect(nombreCompleto(paciente('Muñoz Álvarez'))).toBe('Begoña Muñoz Álvarez');
  });

  it('un paciente sin filiar no revienta la pantalla', () => {
    const sinFiliar: Patient = { resourceType: 'Patient' };

    expect(apellidos(sinFiliar)).toBe('');
    expect(nombreCompleto(sinFiliar)).toBe('');
  });
});

describe('los identificadores', () => {
  const conDos: Patient = {
    resourceType: 'Patient',
    identifier: [
      { system: 'urn:oid:1.3.6.1.4.1.19126.3', value: '12345678Z' },
      { system: 'https://aojeda006.github.io/HispaLIS/sid/nhc', value: '00000042' },
    ],
  };

  it('se eligen por su system y no por el orden', () => {
    // El DNI y el NHC son dos cadenas de dígitos indistinguibles a ojo. Coger «el primero» pone uno
    // donde iba el otro, y nada falla: simplemente deja de encontrar al paciente.
    expect(identificador(conDos, 'https://aojeda006.github.io/HispaLIS/sid/nhc')).toBe('00000042');
  });

  it('el que no consta se devuelve vacío, no se inventa', () => {
    expect(identificador(conDos, 'https://aojeda006.github.io/HispaLIS/sid/nuhsa')).toBe('');
  });
});

describe('las fechas', () => {
  it('llevan la hora, porque dos informes del mismo día son lo normal', () => {
    expect(fechaLegible('2026-08-05T09:30:00Z')).toMatch(/5\/8\/26.+\d{1,2}:\d{2}/);
  });

  it('lo que no se puede interpretar se muestra tal cual, no como «Invalid Date»', () => {
    expect(fechaLegible('vete a saber')).toBe('vete a saber');
    expect(fechaLegible(undefined)).toBe('');
  });
});

function resultado(parcial: Partial<Observation>): Observation {
  return {
    resourceType: 'Observation',
    status: 'final',
    code: { coding: [{ code: 'GLU' }] },
    ...parcial,
  };
}

describe('el valor del resultado', () => {
  it('nunca se muestra sin su unidad', () => {
    const glucosa = resultado({
      valueQuantity: { value: 92, unit: 'mg/dL', code: 'mg/dL' },
    });

    expect(valorConUnidad(glucosa)).toBe('92 mg/dL');
  });

  it('usa la unidad que se imprime y no el código UCUM', () => {
    // `u[IU]/mL` es correcto y es ilegible en un informe. `unit` está justamente para eso.
    const tsh = resultado({ valueQuantity: { value: 8.4, unit: 'µUI/mL', code: 'u[IU]/mL' } });

    expect(valorConUnidad(tsh)).toBe('8,4 µUI/mL');
  });

  it('escribe los decimales a la española', () => {
    const creatinina = resultado({ valueQuantity: { value: 1.05, unit: 'mg/dL' } });

    expect(valorConUnidad(creatinina)).toBe('1,05 mg/dL');
  });

  it('muestra los cualitativos por su término', () => {
    const legionella = resultado({
      valueCodeableConcept: {
        coding: [{ system: SNOMED, code: '260385009', display: 'Negativo' }],
      },
    });

    expect(valorConUnidad(legionella)).toBe('Negativo');
  });

  it('dice que no hay resultado en vez de dejar el hueco en blanco', () => {
    // Un hueco vacío se lee como «aún no ha llegado» y también como «llegó y es normal».
    expect(valorConUnidad(resultado({}))).toBe(SIN_VALOR);
  });
});

describe('el rango de referencia', () => {
  const glucosa = resultado({
    valueQuantity: { value: 92, unit: 'mg/dL' },
    referenceRange: [{ low: { value: 70, unit: 'mg/dL' }, high: { value: 100, unit: 'mg/dL' } }],
  });

  const hemoglobina = resultado({
    valueQuantity: { value: 13, unit: 'g/dL' },
    referenceRange: [
      {
        low: { value: 13.5, unit: 'g/dL' },
        high: { value: 17.5, unit: 'g/dL' },
        appliesTo: [{ coding: [{ system: SNOMED, code: '248153007' }] }],
      },
      {
        low: { value: 12, unit: 'g/dL' },
        high: { value: 16, unit: 'g/dL' },
        appliesTo: [{ coding: [{ system: SNOMED, code: '248152002' }] }],
      },
    ],
  });

  it('se muestra junto al valor', () => {
    expect(rangoDeReferencia(glucosa, 'female')).toBe('70 – 100 mg/dL');
  });

  it('elige el rango que le toca al sexo del paciente', () => {
    // La misma hemoglobina de 13 g/dL es normal en una mujer y baja en un hombre. Mostrar el rango
    // equivocado cambia la lectura del resultado sin cambiar el resultado.
    expect(rangoDeReferencia(hemoglobina, 'female')).toBe('12 – 16 g/dL');
    expect(rangoDeReferencia(hemoglobina, 'male')).toBe('13,5 – 17,5 g/dL');
  });

  it('no elige ninguno de los dos si el sexo no consta', () => {
    // Enseñar el de hombre a un paciente sin sexo registrado es inventarse un dato clínico.
    expect(rangoDeReferencia(hemoglobina, undefined)).toBe('');
  });

  it('el rango común vale para cualquiera', () => {
    expect(rangoDeReferencia(glucosa, undefined)).toBe('70 – 100 mg/dL');
  });

  it('una prueba cualitativa no tiene rango y no se inventa uno', () => {
    expect(rangoDeReferencia(resultado({}), 'male')).toBe('');
  });

  it('un rango abierto por abajo se escribe «hasta»', () => {
    const pcr = resultado({ referenceRange: [{ high: { value: 5, unit: 'mg/L' } }] });

    expect(rangoDeReferencia(pcr, 'male')).toBe('hasta 5 mg/L');
  });
});
