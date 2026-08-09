import 'package:flutter_test/flutter_test.dart';
import 'package:hispalis_ciudadano/datos/fhir/mapeadores.dart';
import 'package:hispalis_ciudadano/dominio/paciente.dart';
import 'package:hispalis_ciudadano/dominio/resultado.dart';

Map<String, Object?> _paciente({
  required String nombre,
  required String apellidos,
  String? genero,
}) => {
  'resourceType': 'Patient',
  'id': 'p1',
  'name': [
    {
      'family': apellidos,
      'given': [nombre],
    },
  ],
  'gender': ?genero,
};

Map<String, Object?> _observacion({
  Object? valor,
  List<Object?> rangos = const [],
  String estado = 'final',
}) => {
  'resourceType': 'Observation',
  'id': 'o1',
  'status': estado,
  'code': {'text': 'Hemoglobina'},
  'effectiveDateTime': '2026-08-08T09:30:00+02:00',
  'valueQuantity': ?valor,
  if (rangos.isNotEmpty) 'referenceRange': rangos,
};

Map<String, Object?> _rangoDe({required double bajo, required double alto, String? sexoSnomed}) => {
  'low': {'value': bajo, 'unit': 'g/dL', 'system': 'http://unitsofmeasure.org', 'code': 'g/dL'},
  'high': {'value': alto, 'unit': 'g/dL', 'system': 'http://unitsofmeasure.org', 'code': 'g/dL'},
  if (sexoSnomed != null)
    'appliesTo': [
      {
        'coding': [
          {'system': 'http://snomed.info/sct', 'code': sexoSnomed},
        ],
      },
    ],
};

void main() {
  group('Paciente', () {
    // Charset obligatorio del proyecto. No es folclore: la eñe y las tildes son donde se rompen las
    // codificaciones, y un apellido mal escrito en la pantalla de una analítica es un error grave.
    for (final apellidos in ['MUÑOZ ÁLVAREZ', 'PEÑA MUÑOZ', 'ÁLVAREZ PEÑA']) {
      test('«$apellidos» llega entero y sin tocar', () {
        final paciente = MapeadorFhir.paciente(_paciente(nombre: 'Lucía', apellidos: apellidos));

        expect(paciente.apellidos, apellidos);
        expect(paciente.nombreCompleto, 'Lucía $apellidos');
      });
    }

    test('un apellido con partícula NO se parte por el espacio', () {
      // Partir por el primer espacio produciría «de» y «la Torre Gómez». El nombre familiar es uno.
      final paciente = MapeadorFhir.paciente(
        _paciente(nombre: 'Juan Antonio', apellidos: 'de la Torre Gómez'),
      );

      expect(paciente.apellidos, 'de la Torre Gómez');
      expect(paciente.nombreDePila, 'Juan Antonio');
    });

    test('el sexo se traduce, y lo que no se conoce no se adivina', () {
      expect(
        MapeadorFhir.paciente(_paciente(nombre: 'A', apellidos: 'B', genero: 'female')).sexo,
        SexoRegistrado.mujer,
      );
      expect(
        MapeadorFhir.paciente(_paciente(nombre: 'A', apellidos: 'B', genero: 'male')).sexo,
        SexoRegistrado.hombre,
      );
      expect(
        MapeadorFhir.paciente(_paciente(nombre: 'A', apellidos: 'B', genero: 'other')).sexo,
        SexoRegistrado.noConsta,
      );
      expect(
        MapeadorFhir.paciente(_paciente(nombre: 'A', apellidos: 'B')).sexo,
        SexoRegistrado.noConsta,
      );
    });
  });

  group('Resultado', () {
    test('el valor numérico llega SIEMPRE con su unidad', () {
      final resultado = MapeadorFhir.resultado(
        _observacion(valor: {'value': 13.4, 'unit': 'g/dL', 'code': 'g/dL'}),
        SexoRegistrado.mujer,
      );

      expect(resultado.valor, isA<ValorNumerico>());
      expect((resultado.valor as ValorNumerico).cifra, 13.4);
      expect((resultado.valor as ValorNumerico).unidad, 'g/dL');
    });

    test('una cifra sin unidad NO se convierte en un número en pantalla', () {
      // El tipo del dominio no admite una cifra sin unidad, así que el mapeador no puede
      // «apañarlo»: prefiere decir que no hay valor a soltar un 13,4 que no significa nada.
      final resultado = MapeadorFhir.resultado(
        _observacion(valor: {'value': 13.4}),
        SexoRegistrado.mujer,
      );

      expect(resultado.valor, isA<SinValor>());
    });

    test('si falta `unit` se usa el código UCUM antes que quedarse sin unidad', () {
      final resultado = MapeadorFhir.resultado(
        _observacion(valor: {'value': 4.2, 'code': 'mmol/L'}),
        SexoRegistrado.hombre,
      );

      expect((resultado.valor as ValorNumerico).unidad, 'mmol/L');
    });

    test('cada sexo recibe SU rango de referencia', () {
      final rangos = [
        _rangoDe(bajo: 13, alto: 17, sexoSnomed: '248153007'),
        _rangoDe(bajo: 12, alto: 15, sexoSnomed: '248152002'),
      ];

      final hombre = MapeadorFhir.resultado(
        _observacion(valor: {'value': 13.4, 'unit': 'g/dL'}, rangos: rangos),
        SexoRegistrado.hombre,
      );
      final mujer = MapeadorFhir.resultado(
        _observacion(valor: {'value': 13.4, 'unit': 'g/dL'}, rangos: rangos),
        SexoRegistrado.mujer,
      );

      expect(hombre.rango!.bajo, 13);
      expect(hombre.rango!.alto, 17);
      expect(mujer.rango!.bajo, 12);
      expect(mujer.rango!.alto, 15);
    });

    test('con el sexo sin constar NO se enseña un rango específico de sexo', () {
      final resultado = MapeadorFhir.resultado(
        _observacion(
          valor: {'value': 13.4, 'unit': 'g/dL'},
          rangos: [
            _rangoDe(bajo: 13, alto: 17, sexoSnomed: '248153007'),
            _rangoDe(bajo: 12, alto: 15, sexoSnomed: '248152002'),
          ],
        ),
        SexoRegistrado.noConsta,
      );

      // Ni el de hombre ni el de mujer: no consta, y punto. La pantalla lo dirá con todas las
      // letras en vez de inventarse a qué población pertenece esta persona.
      expect(resultado.rango, isNull);
    });

    test('el rango común vale para quien no tiene uno propio', () {
      final resultado = MapeadorFhir.resultado(
        _observacion(
          valor: {'value': 13.4, 'unit': 'g/dL'},
          rangos: [_rangoDe(bajo: 12, alto: 16)],
        ),
        SexoRegistrado.noConsta,
      );

      expect(resultado.rango!.bajo, 12);
      expect(resultado.rango!.unidad, 'g/dL');
    });

    test('los estados de R5 se traducen a lo que le importa a la persona', () {
      EstadoDelResultado estadoDe(String status) =>
          MapeadorFhir.resultado(_observacion(estado: status), SexoRegistrado.noConsta).estado;

      expect(estadoDe('preliminary'), EstadoDelResultado.preliminar);
      expect(estadoDe('registered'), EstadoDelResultado.preliminar);
      expect(estadoDe('final'), EstadoDelResultado.validado);
      expect(estadoDe('corrected'), EstadoDelResultado.corregido);
      expect(estadoDe('amended'), EstadoDelResultado.corregido);
      expect(estadoDe('cancelled'), EstadoDelResultado.anulado);
      expect(estadoDe('entered-in-error'), EstadoDelResultado.anulado);
      // Un estado que la app no conoce no se traduce a «validado» por comodidad.
      expect(estadoDe('lo-que-sea'), EstadoDelResultado.desconocido);
      expect(EstadoDelResultado.desconocido.loFirmaUnFacultativo, isFalse);
    });

    test('una prueba refleja llega con la frase que la explica, no con un código', () {
      // R5: `triggeredBy`. La frase la redacta el laboratorio; la app no compone ninguna, porque
      // tendría que decidir el género de cada nombre de prueba para hacerlo bien.
      final t4l = _observacion(valor: {'value': 0.9, 'unit': 'ng/dL'})
        ..['triggeredBy'] = [
          {
            'observation': {'reference': 'Observation/tsh'},
            'type': 'reflex',
            'reason': 'Derivada de un TSH alterado.',
          },
        ];

      expect(
        MapeadorFhir.resultado(t4l, SexoRegistrado.noConsta).porQueExiste,
        'Derivada de un TSH alterado.',
      );
    });

    test('un disparo sin frase no se enseña: «derivada de otra» no le aclara nada a nadie', () {
      final mudo = _observacion(valor: {'value': 0.9, 'unit': 'ng/dL'})
        ..['triggeredBy'] = [
          {
            'observation': {'reference': 'Observation/tsh'},
            'type': 'repeat',
          },
        ];

      expect(MapeadorFhir.resultado(mudo, SexoRegistrado.noConsta).porQueExiste, isNull);
    });

    test('la mayoría de los resultados no vienen de ninguna otra determinación', () {
      expect(
        MapeadorFhir.resultado(
          _observacion(valor: {'value': 13.5, 'unit': 'g/dL'}),
          SexoRegistrado.noConsta,
        ).porQueExiste,
        isNull,
      );
    });
  });

  group('Informe', () {
    test('un resultado que el laboratorio no ha devuelto se omite sin reventar', () {
      // El servidor OMITE del resultado de búsqueda lo que la sesión no puede ver. Si el informe
      // apunta a un `Observation` que no llegó, eso es una decisión de consentimiento, no un fallo.
      final informe = MapeadorFhir.informe(
        {
          'resourceType': 'DiagnosticReport',
          'id': 'inf-1',
          'issued': '2026-08-08T10:00:00+02:00',
          'result': [
            {'reference': 'Observation/o1'},
            {'reference': 'Observation/no-llego'},
          ],
        },
        {
          'Observation/o1': const Resultado(
            id: 'o1',
            prueba: 'Hemoglobina',
            valor: ValorNumerico(13.4, 'g/dL'),
            estado: EstadoDelResultado.validado,
          ),
        },
      );

      expect(informe.resultados, hasLength(1));
      expect(informe.resultados.single.id, 'o1');
      expect(informe.validadoPorFacultativo, isTrue);
    });

    test('un informe con algo sin firmar no está validado', () {
      final informe = MapeadorFhir.informe(
        {
          'resourceType': 'DiagnosticReport',
          'id': 'inf-1',
          'issued': '2026-08-08T10:00:00+02:00',
          'result': [
            {'reference': 'Observation/o1'},
          ],
        },
        {
          'Observation/o1': const Resultado(
            id: 'o1',
            prueba: 'Hemoglobina',
            valor: ValorNumerico(13.4, 'g/dL'),
            estado: EstadoDelResultado.preliminar,
          ),
        },
      );

      expect(informe.validadoPorFacultativo, isFalse);
      expect(informe.pendientesDeValidar, 1);
    });
  });
}
