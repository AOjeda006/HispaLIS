import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:hispalis_ciudadano/nucleo/seguridad/sesion.dart';

import '../ayudas/dobles.dart';

void main() {
  group('Sesión', () {
    test('lo que se guarda se recupera igual', () async {
      final almacen = AlmacenDeMentira();
      final datos = DatosDeSesion(
        testigo: 'testigo-1',
        caducaEn: DateTime.utc(2026, 8, 8, 12),
        scopesConcedidos: const {'openid', 'patient/*.rs'},
        testigoDeRefresco: 'refresco-1',
        paciente: 'paciente-1',
        fhirUser: 'Patient/paciente-1',
      );

      await Sesion(almacen).abrir(datos);
      final otra = Sesion(almacen);
      await otra.recordar();

      expect(otra.datos!.testigo, 'testigo-1');
      expect(otra.datos!.caducaEn, DateTime.utc(2026, 8, 8, 12));
      expect(otra.datos!.scopesConcedidos, {'openid', 'patient/*.rs'});
      expect(otra.datos!.testigoDeRefresco, 'refresco-1');
      expect(otra.datos!.pacienteEnContexto, 'paciente-1');
    });

    test('cerrar sesión no deja ni el testigo ni nada más', () async {
      final almacen = AlmacenDeMentira()..contenido['algo.que.alguien.guardo'] = 'lo que sea';
      final sesion = Sesion(almacen);
      await sesion.abrir(
        DatosDeSesion(
          testigo: 'testigo-1',
          caducaEn: DateTime.now().add(const Duration(minutes: 5)),
          scopesConcedidos: const {'patient/*.rs'},
        ),
      );

      await sesion.cerrar();

      expect(sesion.datos, isNull);
      // `borrarTodo` y no borrar una clave: lo que se guarde mañana también tiene que irse.
      expect(almacen.contenido, isEmpty);
    });

    test('sin nada guardado no hay sesión que recordar', () async {
      final sesion = Sesion(AlmacenDeMentira());

      await sesion.recordar();

      expect(sesion.datos, isNull);
      expect(sesion.activa, isFalse);
    });

    test('el testigo caducado no cuenta como sesión activa', () async {
      final sesion = Sesion(AlmacenDeMentira());
      await sesion.abrir(
        DatosDeSesion(
          testigo: 'testigo-1',
          caducaEn: DateTime.now().subtract(const Duration(seconds: 1)),
          scopesConcedidos: const {},
        ),
      );

      expect(sesion.activa, isFalse);
      expect(sesion.datos!.caducadaCon(Duration.zero), isTrue);
    });

    test('el paciente se deduce del `fhirUser` cuando no hay contexto de lanzamiento', () {
      final sinContexto = DatosDeSesion(
        testigo: 'testigo-1',
        caducaEn: DateTime.utc(2026),
        scopesConcedidos: const {},
        fhirUser: 'Patient/paciente-9',
      );

      expect(sinContexto.pacienteEnContexto, 'paciente-9');
    });

    test('un `fhirUser` que no es un paciente no se convierte en uno', () {
      final profesional = DatosDeSesion(
        testigo: 'testigo-1',
        caducaEn: DateTime.utc(2026),
        scopesConcedidos: const {},
        fhirUser: 'Practitioner/fac-1',
      );

      expect(profesional.pacienteEnContexto, isNull);
    });

    test('lo guardado es el JSON de la sesión y nada clínico', () async {
      final almacen = AlmacenDeMentira();
      await Sesion(almacen).abrir(
        DatosDeSesion(
          testigo: 'testigo-1',
          caducaEn: DateTime.utc(2026, 8, 8, 12),
          scopesConcedidos: const {'patient/*.rs'},
          paciente: 'paciente-1',
        ),
      );

      final guardado = jsonDecode(almacen.contenido[Sesion.clave]!) as Map<String, Object?>;

      // Credenciales y contexto. Ni un resultado, ni un nombre, ni una fecha de nacimiento.
      expect(guardado.keys, {'testigo', 'caducaEn', 'scopes', 'refresco', 'paciente', 'fhirUser'});
    });
  });
}
