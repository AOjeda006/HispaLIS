import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:hispalis_ciudadano/dominio/informe.dart';
import 'package:hispalis_ciudadano/dominio/paciente.dart';
import 'package:hispalis_ciudadano/dominio/repositorio_de_informes.dart';
import 'package:hispalis_ciudadano/dominio/resultado.dart';
import 'package:hispalis_ciudadano/nucleo/errores/error_de_la_app.dart';
import 'package:hispalis_ciudadano/nucleo/seguridad/sesion.dart';
import 'package:hispalis_ciudadano/presentacion/informes/informes_vm.dart';
import 'package:hispalis_ciudadano/presentacion/informes/pantalla_de_informe.dart';
import 'package:hispalis_ciudadano/presentacion/informes/pantalla_de_informes.dart';
import 'package:hispalis_ciudadano/presentacion/informes/widgets/aviso_de_validacion.dart';
import 'package:hispalis_ciudadano/presentacion/tema.dart';
import 'package:provider/provider.dart';

import '../ayudas/dobles.dart';

const Paciente _lucia = Paciente(
  id: 'paciente-1',
  nombreDePila: 'Lucía',
  apellidos: 'MUÑOZ ÁLVAREZ',
  sexo: SexoRegistrado.mujer,
);

final Informe _validado = Informe.emitido(
  id: 'inf-1',
  fecha: DateTime(2026, 8, 1, 10),
  resultados: [
    Resultado(
      id: 'o1',
      prueba: 'Hemoglobina',
      valor: const ValorNumerico(13.4, 'g/dL'),
      rango: const RangoDeReferencia(unidad: 'g/dL', bajo: 12, alto: 15),
      estado: EstadoDelResultado.validado,
      medidoEn: DateTime(2026, 8, 1, 9, 30),
    ),
    const Resultado(
      id: 'o2',
      prueba: 'Anticuerpos irregulares',
      valor: ValorTextual('Negativo'),
      estado: EstadoDelResultado.validado,
    ),
    // La prueba refleja: existe porque otra salió alterada, y el informe tiene que decirlo.
    const Resultado(
      id: 'o4',
      prueba: 'T4 libre',
      valor: ValorNumerico(0.9, 'ng/dL'),
      rango: RangoDeReferencia(unidad: 'ng/dL', bajo: 0.7, alto: 1.9),
      estado: EstadoDelResultado.validado,
      porQueExiste:
          'Derivada de un TSH alterado: el protocolo de función tiroidea del laboratorio '
          'añade la T4 libre cuando la TSH cae fuera de su rango de referencia.',
    ),
  ],
);

final Informe _enCurso = Informe.enCurso(
  fecha: DateTime(2026, 8, 8, 9),
  resultados: [
    Resultado(
      id: 'o3',
      prueba: 'Potasio',
      valor: const ValorNumerico(4.2, 'mmol/L'),
      rango: const RangoDeReferencia(unidad: 'mmol/L', bajo: 3.5, alto: 5.1),
      estado: EstadoDelResultado.preliminar,
      medidoEn: DateTime(2026, 8, 8, 9),
    ),
  ],
);

/// Un repositorio que devuelve lo que se le diga, o falla como se le diga.
final class _RepositorioDeMentira implements RepositorioDeInformes {
  _RepositorioDeMentira({this.historialDe, this.falla});

  final Historial? historialDe;
  final ErrorDeLaApp? falla;

  @override
  Future<Historial> historial(String pacienteId) async {
    if (falla != null) {
      throw falla!;
    }
    return historialDe!;
  }
}

Future<InformesVm> _vmCargado(
  RepositorioDeInformes repositorio, {
  String? paciente = 'paciente-1',
}) async {
  final sesion = Sesion(AlmacenDeMentira());
  await sesion.abrir(
    DatosDeSesion(
      testigo: 'testigo-1',
      caducaEn: DateTime.now().add(const Duration(minutes: 30)),
      scopesConcedidos: const {'patient/*.rs'},
      paciente: paciente,
    ),
  );
  final vm = InformesVm(repositorio: repositorio, sesion: sesion);
  await vm.cargar();
  return vm;
}

Widget _conVm(InformesVm vm, Widget pantalla) => ChangeNotifierProvider<InformesVm>.value(
  value: vm,
  child: MaterialApp(theme: Tema.claro, home: pantalla),
);

void main() {
  group('Detalle de una analítica', () {
    testWidgets('cada resultado sale con su valor, su unidad y su rango', (tester) async {
      final vm = await _vmCargado(
        _RepositorioDeMentira(
          historialDe: Historial(paciente: _lucia, informes: [_validado]),
        ),
      );

      await tester.pumpWidget(_conVm(vm, const PantallaDeInforme(informeId: 'inf-1')));

      expect(find.text('Hemoglobina'), findsOneWidget);
      expect(find.text('13,4 g/dL'), findsOneWidget);
      expect(find.text('Valores de referencia: 12 – 15 g/dL'), findsOneWidget);
    });

    testWidgets('una prueba refleja explica CON PALABRAS por qué está ahí', (tester) async {
      final vm = await _vmCargado(
        _RepositorioDeMentira(
          historialDe: Historial(paciente: _lucia, informes: [_validado]),
        ),
      );

      await tester.pumpWidget(_conVm(vm, const PantallaDeInforme(informeId: 'inf-1')));

      // Con palabras y no con un icono: un icono hay que aprendérselo y no lo lee un lector de
      // pantalla. La frase la trae el laboratorio; la app no compone ninguna.
      expect(find.textContaining('Derivada de un TSH alterado'), findsOneWidget);
    });

    testWidgets('una prueba sin rango lo DICE en vez de dejar el hueco', (tester) async {
      final vm = await _vmCargado(
        _RepositorioDeMentira(
          historialDe: Historial(paciente: _lucia, informes: [_validado]),
        ),
      );

      await tester.pumpWidget(_conVm(vm, const PantallaDeInforme(informeId: 'inf-1')));

      expect(find.text('Anticuerpos irregulares'), findsOneWidget);
      expect(find.text('Negativo'), findsOneWidget);
      expect(
        find.text('Valores de referencia: No consta rango de referencia para esta prueba'),
        findsOneWidget,
      );
    });

    testWidgets('NO hay ningún resultado sin línea de rango', (tester) async {
      final vm = await _vmCargado(
        _RepositorioDeMentira(
          historialDe: Historial(paciente: _lucia, informes: [_validado]),
        ),
      );

      await tester.pumpWidget(_conVm(vm, const PantallaDeInforme(informeId: 'inf-1')));

      // Tantas líneas de rango como determinaciones: ninguna se queda sin contexto.
      expect(
        find.textContaining('Valores de referencia:'),
        findsNWidgets(_validado.resultados.length),
      );
    });

    testWidgets('una analítica sin validar lo dice con todas las letras y arriba', (tester) async {
      final vm = await _vmCargado(
        _RepositorioDeMentira(
          historialDe: Historial(paciente: _lucia, informes: [_enCurso]),
        ),
      );

      await tester.pumpWidget(_conVm(vm, const PantallaDeInforme(informeId: 'en-curso')));

      expect(find.text(AvisoDeValidacion.textoPendiente), findsOneWidget);
      expect(find.textContaining('NO está validada por el facultativo'), findsOneWidget);
      expect(find.text('Pendiente de validar por el facultativo'), findsOneWidget);
      // Y el aviso va por delante del primer valor, no al pie.
      final avisoY = tester.getTopLeft(find.text(AvisoDeValidacion.textoPendiente)).dy;
      final valorY = tester.getTopLeft(find.text('4,2 mmol/L')).dy;
      expect(avisoY, lessThan(valorY));
    });

    testWidgets('una analítica validada no lleva el aviso', (tester) async {
      final vm = await _vmCargado(
        _RepositorioDeMentira(
          historialDe: Historial(paciente: _lucia, informes: [_validado]),
        ),
      );

      await tester.pumpWidget(_conVm(vm, const PantallaDeInforme(informeId: 'inf-1')));

      expect(find.text(AvisoDeValidacion.textoPendiente), findsNothing);
      expect(find.text('Validado por el facultativo'), findsNWidgets(_validado.resultados.length));
    });
  });

  group('Lista de analíticas', () {
    testWidgets('los apellidos salen enteros, con eñes y tildes', (tester) async {
      final vm = await _vmCargado(
        _RepositorioDeMentira(
          historialDe: Historial(paciente: _lucia, informes: [_validado, _enCurso]),
        ),
      );

      await tester.pumpWidget(_conVm(vm, const PantallaDeInformes()));
      await tester.pumpAndSettle();

      expect(find.text('Lucía MUÑOZ ÁLVAREZ'), findsOneWidget);
      expect(find.text('Informe de laboratorio'), findsOneWidget);
      expect(find.text('Analítica en curso'), findsOneWidget);
      expect(find.text('Validada por el facultativo'), findsOneWidget);
      expect(find.text('Pendiente de validar'), findsOneWidget);
    });

    testWidgets('sin resultados se dice que no hay, no se enseña una lista vacía', (tester) async {
      final vm = await _vmCargado(
        _RepositorioDeMentira(
          historialDe: const Historial(paciente: _lucia, informes: []),
        ),
      );

      await tester.pumpWidget(_conVm(vm, const PantallaDeInformes()));
      await tester.pumpAndSettle();

      expect(find.textContaining('Todavía no hay resultados tuyos'), findsOneWidget);
    });

    testWidgets('un `403` del laboratorio no enseña ni un dato de nadie', (tester) async {
      final vm = await _vmCargado(_RepositorioDeMentira(falla: ErrorDeLaApp.sinPermiso()));

      await tester.pumpWidget(_conVm(vm, const PantallaDeInformes()));
      await tester.pumpAndSettle();

      expect(find.textContaining('Solo puedes ver tus propios resultados'), findsOneWidget);
      // Ni el nombre, ni una cifra, ni una prueba: la pantalla se queda con el mensaje y nada más.
      expect(find.textContaining('MUÑOZ'), findsNothing);
      expect(find.textContaining('Hemoglobina'), findsNothing);
      expect(find.textContaining('13,4'), findsNothing);
    });

    testWidgets('una identidad sin historia vinculada se explica en español', (tester) async {
      final vm = await _vmCargado(
        _RepositorioDeMentira(
          historialDe: Historial(paciente: _lucia, informes: [_validado]),
        ),
        paciente: null,
      );

      await tester.pumpWidget(_conVm(vm, const PantallaDeInformes()));
      await tester.pumpAndSettle();

      expect(
        find.textContaining('no está vinculada a ninguna historia de este laboratorio'),
        findsOneWidget,
      );
      expect(find.textContaining('Hemoglobina'), findsNothing);
    });
  });
}
