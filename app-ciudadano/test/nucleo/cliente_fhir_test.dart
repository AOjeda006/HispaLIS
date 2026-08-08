import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:hispalis_ciudadano/nucleo/errores/error_de_la_app.dart';
import 'package:hispalis_ciudadano/nucleo/red/cliente_fhir.dart';
import 'package:hispalis_ciudadano/nucleo/red/interceptor_del_testigo.dart';
import 'package:hispalis_ciudadano/nucleo/seguridad/sesion.dart';

import '../ayudas/dobles.dart';

final Uri _base = Uri.parse('http://laboratorio.test/fhir');

Future<Sesion> _sesionAbierta({String testigo = 'testigo-1', Duration? vigencia}) async {
  final sesion = Sesion(AlmacenDeMentira());
  await sesion.abrir(
    DatosDeSesion(
      testigo: testigo,
      caducaEn: DateTime.now().add(vigencia ?? const Duration(minutes: 30)),
      scopesConcedidos: const {'patient/*.rs'},
      paciente: 'paciente-1',
    ),
  );
  return sesion;
}

void main() {
  group('Cliente FHIR', () {
    test('pone el testigo en las llamadas al laboratorio', () async {
      final red = RedDeMentira({
        '/fhir/Patient/paciente-1': const RespuestaPreparada({
          'resourceType': 'Patient',
          'id': 'paciente-1',
        }),
      });
      final cliente = _clienteCon(red, await _sesionAbierta());

      await cliente.leer('Patient/paciente-1');

      expect(red.peticiones.single.headers['Authorization'], 'Bearer testigo-1');
    });

    test('un `403` se cuenta como «esto no es tuyo» y sin filtrar el recurso ajeno', () async {
      final red = RedDeMentira({
        '/fhir/Patient/otro': const RespuestaPreparada({
          'resourceType': 'OperationOutcome',
          'issue': [
            {
              'severity': 'error',
              'code': 'forbidden',
              'diagnostics': 'El compartimento Patient/otro no corresponde al del testigo',
            },
          ],
        }, estado: 403),
      });
      final cliente = _clienteCon(red, await _sesionAbierta());

      await expectLater(
        cliente.leer('Patient/otro'),
        throwsA(
          isA<ErrorDeLaApp>()
              .having((e) => e.codigo, 'codigo', CodigoDeError.sinPermiso)
              // El diagnóstico del servidor habla de compartimentos: no es lo que se le enseña a
              // una persona, y además nombra el recurso ajeno.
              .having((e) => e.mensaje, 'mensaje', isNot(contains('compartimento')))
              .having((e) => e.mensaje, 'mensaje', isNot(contains('otro'))),
        ),
      );
    });

    test('sin red, se dice que no se ha podido contactar', () async {
      final cliente = ClienteFhir(
        baseFhir: _base,
        http: Dio(BaseOptions(baseUrl: '$_base/'))..httpClientAdapter = const _RedCaida(),
      );

      await expectLater(
        cliente.leer('Patient/paciente-1'),
        throwsA(isA<ErrorDeLaApp>().having((e) => e.codigo, 'codigo', CodigoDeError.sinRed)),
      );
    });

    test('la búsqueda sigue el enlace `next` y no construye la página a mano', () async {
      final red = RedDeMentira({
        '/fhir/Observation': RespuestaPreparada({
          'resourceType': 'Bundle',
          'link': [
            {'relation': 'next', 'url': 'http://laboratorio.test/fhir?_getpages=abc&_page=2'},
          ],
          'entry': [
            {
              'resource': {'resourceType': 'Observation', 'id': 'o1'},
            },
          ],
        }),
        '/fhir': const RespuestaPreparada({
          'resourceType': 'Bundle',
          'entry': [
            {
              'resource': {'resourceType': 'Observation', 'id': 'o2'},
            },
          ],
        }),
      });
      final cliente = _clienteCon(red, await _sesionAbierta());

      final encontrados = await cliente.buscar('Observation', {'patient': 'Patient/paciente-1'});

      expect(encontrados.map((r) => r['id']), ['o1', 'o2']);
      expect(red.peticiones.last.uri.queryParameters['_getpages'], 'abc');
    });
  });

  group('Renovación desde el interceptor', () {
    test('un `401` renueva una vez, reintenta y devuelve el recurso', () async {
      final red = _RedQueCaduca();
      var renovaciones = 0;
      final sesion = await _sesionAbierta();
      final cliente = _clienteQueRenueva(red, sesion, () async {
        renovaciones++;
        await sesion.abrir(
          DatosDeSesion(
            testigo: 'testigo-2',
            caducaEn: DateTime.now().add(const Duration(minutes: 30)),
            scopesConcedidos: const {'patient/*.rs'},
            paciente: 'paciente-1',
          ),
        );
        return true;
      });

      final recurso = await cliente.leer('Patient/paciente-1');

      expect(recurso['id'], 'paciente-1');
      expect(renovaciones, 1);
      expect(red.testigos, ['Bearer testigo-1', 'Bearer testigo-2']);
    });

    test('si la renovación tampoco vale, la sesión se da por caducada sin bucle', () async {
      final red = _RedQueCaduca(siempre: true);
      var renovaciones = 0;
      final sesion = await _sesionAbierta();
      final cliente = _clienteQueRenueva(red, sesion, () async {
        renovaciones++;
        return true;
      });

      await expectLater(
        cliente.leer('Patient/paciente-1'),
        throwsA(
          isA<ErrorDeLaApp>().having((e) => e.codigo, 'codigo', CodigoDeError.sesionCaducada),
        ),
      );
      // Exactamente una: el reintento se marca y no se vuelve a entrar.
      expect(renovaciones, 1);
      expect(red.testigos, hasLength(2));
    });
  });
}

/// Monta el cliente igual que la fábrica de producción, pero contra una red de mentira.
///
/// Se repite el montaje en vez de abrirle una puerta al cliente para que los tests le cambien el
/// transporte: lo que se está probando es el interceptor, y una API pública que solo existe para los
/// tests acaba usándose desde otro sitio.
ClienteFhir _clienteQueRenueva(HttpClientAdapter red, Sesion sesion, RenovadorDeSesion renovar) {
  final http = Dio(BaseOptions(baseUrl: '$_base/'))..httpClientAdapter = red;
  final interceptor = InterceptorDelTestigo(sesion: sesion, renovar: renovar, baseFhir: _base)
    ..cliente = http;
  http.interceptors.add(interceptor);
  return ClienteFhir(baseFhir: _base, http: http);
}

ClienteFhir _clienteCon(RedDeMentira red, Sesion sesion) => ClienteFhir(
  baseFhir: _base,
  http: red.comoDio(BaseOptions(baseUrl: '$_base/'))
    ..options.headers['Authorization'] = 'Bearer ${sesion.datos!.testigo}',
);

/// Una red que contesta `401` la primera vez y bien la segunda.
final class _RedQueCaduca implements HttpClientAdapter {
  _RedQueCaduca({this.siempre = false});

  final bool siempre;

  /// Los testigos con los que se ha llamado, en orden.
  final List<String> testigos = [];

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    testigos.add('${options.headers['Authorization']}');
    final caduco = siempre || testigos.length == 1;
    return ResponseBody.fromString(
      jsonEncode(
        caduco
            ? {'resourceType': 'OperationOutcome'}
            : {'resourceType': 'Patient', 'id': 'paciente-1'},
      ),
      caduco ? 401 : 200,
      headers: const {
        Headers.contentTypeHeader: ['application/json'],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}

/// Una red que no llega a ninguna parte.
final class _RedCaida implements HttpClientAdapter {
  const _RedCaida();

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async => throw DioException.connectionError(
    requestOptions: options,
    reason: 'sin red en el test',
  );

  @override
  void close({bool force = false}) {}
}
