import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:hispalis_ciudadano/datos/repositorio_de_informes_fhir.dart';
import 'package:hispalis_ciudadano/dominio/resultado.dart';
import 'package:hispalis_ciudadano/nucleo/errores/error_de_la_app.dart';
import 'package:hispalis_ciudadano/nucleo/red/cliente_fhir.dart';

import '../ayudas/dobles.dart';

final Uri _base = Uri.parse('http://laboratorio.test/fhir');

const Map<String, Object?> _paciente = {
  'resourceType': 'Patient',
  'id': 'paciente-1',
  'gender': 'female',
  'name': [
    {
      'family': 'MUÑOZ ÁLVAREZ',
      'given': ['Lucía'],
    },
  ],
};

Map<String, Object?> _bundle(List<Map<String, Object?>> recursos) => {
  'resourceType': 'Bundle',
  'type': 'searchset',
  'entry': [
    for (final recurso in recursos) {'resource': recurso},
  ],
};

Map<String, Object?> _observacion(String id, String estado, String cuando) => {
  'resourceType': 'Observation',
  'id': id,
  'status': estado,
  'code': {'text': 'Hemoglobina'},
  'effectiveDateTime': cuando,
  'valueQuantity': {'value': 13.4, 'unit': 'g/dL', 'code': 'g/dL'},
  'referenceRange': [
    {
      'low': {'value': 12, 'unit': 'g/dL'},
      'high': {'value': 15, 'unit': 'g/dL'},
    },
  ],
};

ClienteFhir _cliente(RedDeMentira red) =>
    ClienteFhir(baseFhir: _base, http: red.comoDio(BaseOptions(baseUrl: '$_base/')));

void main() {
  group('Historial del paciente', () {
    test('separa lo emitido de lo que sigue en curso, y descarta lo anulado', () async {
      final red = RedDeMentira({
        '/fhir/Patient/paciente-1': const RespuestaPreparada(_paciente),
        '/fhir/Observation': RespuestaPreparada(
          _bundle([
            _observacion('o1', 'final', '2026-08-01T09:00:00+02:00'),
            _observacion('o2', 'preliminary', '2026-08-08T09:00:00+02:00'),
            _observacion('o3', 'cancelled', '2026-08-08T09:05:00+02:00'),
          ]),
        ),
        '/fhir/DiagnosticReport': RespuestaPreparada(
          _bundle([
            {
              'resourceType': 'DiagnosticReport',
              'id': 'inf-1',
              'status': 'final',
              'issued': '2026-08-01T10:00:00+02:00',
              'result': [
                {'reference': 'Observation/o1'},
              ],
            },
          ]),
        ),
      });

      final historial = await RepositorioDeInformesFhir(_cliente(red)).historial('paciente-1');

      expect(historial.paciente.apellidos, 'MUÑOZ ÁLVAREZ');
      expect(historial.informes, hasLength(2));
      // Lo más reciente primero: la analítica en curso es del día 8.
      expect(historial.informes.first.id, 'en-curso');
      expect(historial.informes.first.emitido, isFalse);
      expect(historial.informes.first.validadoPorFacultativo, isFalse);
      // Lo anulado no está «en curso»: está fuera.
      expect(historial.informes.first.resultados.map((r) => r.id), ['o2']);
      expect(historial.informes.last.id, 'inf-1');
      expect(historial.informes.last.validadoPorFacultativo, isTrue);
    });

    test('el rango que se guarda es el de la mujer que ha entrado', () async {
      final red = RedDeMentira({
        '/fhir/Patient/paciente-1': const RespuestaPreparada(_paciente),
        '/fhir/Observation': RespuestaPreparada(
          _bundle([
            {
              ..._observacion('o1', 'final', '2026-08-01T09:00:00+02:00'),
              'referenceRange': [
                {
                  'low': {'value': 13, 'unit': 'g/dL'},
                  'high': {'value': 17, 'unit': 'g/dL'},
                  'appliesTo': [
                    {
                      'coding': [
                        {'system': 'http://snomed.info/sct', 'code': '248153007'},
                      ],
                    },
                  ],
                },
                {
                  'low': {'value': 12, 'unit': 'g/dL'},
                  'high': {'value': 15, 'unit': 'g/dL'},
                  'appliesTo': [
                    {
                      'coding': [
                        {'system': 'http://snomed.info/sct', 'code': '248152002'},
                      ],
                    },
                  ],
                },
              ],
            },
          ]),
        ),
        '/fhir/DiagnosticReport': RespuestaPreparada(_bundle(const [])),
      });

      final historial = await RepositorioDeInformesFhir(_cliente(red)).historial('paciente-1');
      final resultado = historial.informes.single.resultados.single;

      expect(resultado.rango!.bajo, 12);
      expect(resultado.rango!.alto, 15);
      expect((resultado.valor as ValorNumerico).unidad, 'g/dL');
    });

    test('sin nada del laboratorio, el historial está vacío y no falla', () async {
      final red = RedDeMentira({
        '/fhir/Patient/paciente-1': const RespuestaPreparada(_paciente),
        '/fhir/Observation': RespuestaPreparada(_bundle(const [])),
        '/fhir/DiagnosticReport': RespuestaPreparada(_bundle(const [])),
      });

      final historial = await RepositorioDeInformesFhir(_cliente(red)).historial('paciente-1');

      expect(historial.informes, isEmpty);
    });

    test('pedir la historia de otra persona acaba en «no te lo permite»', () async {
      final red = RedDeMentira({
        '/fhir/Patient/otro-paciente': const RespuestaPreparada({
          'resourceType': 'OperationOutcome',
        }, estado: 403),
      });

      await expectLater(
        RepositorioDeInformesFhir(_cliente(red)).historial('otro-paciente'),
        throwsA(
          isA<ErrorDeLaApp>().having((e) => e.codigo, 'codigo', CodigoDeError.sinPermiso),
        ),
      );
    });
  });
}
