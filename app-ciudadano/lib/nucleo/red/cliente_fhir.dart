import 'package:dio/dio.dart';

import '../errores/error_de_la_app.dart';
import '../seguridad/sesion.dart';
import 'interceptor_del_testigo.dart';

/// El único sitio de la app que habla con la API FHIR del laboratorio.
///
/// Devuelve JSON crudo a propósito: convertirlo en objetos del dominio es trabajo de los mapeadores
/// de `datos/`, y mezclarlo aquí acabaría con el transporte sabiendo qué es un rango de referencia.
final class ClienteFhir {
  /// Construye el cliente sobre un [Dio] ya configurado. Lo usan los tests para poner el suyo.
  ClienteFhir({required this.baseFhir, required Dio http}) : _http = http;

  /// Monta el cliente de producción: base, tiempos de espera y el interceptor del testigo.
  factory ClienteFhir.paraElLaboratorio({
    required Uri baseFhir,
    required Sesion sesion,
    required RenovadorDeSesion renovar,
  }) {
    final http = Dio(
      BaseOptions(
        baseUrl: '$baseFhir/',
        connectTimeout: const Duration(seconds: 10),
        receiveTimeout: const Duration(seconds: 20),
        // FHIR R5 en JSON, y se dice en las dos direcciones.
        headers: {'Accept': 'application/fhir+json'},
        contentType: 'application/fhir+json',
        responseType: ResponseType.json,
      ),
    );
    final testigo = InterceptorDelTestigo(sesion: sesion, renovar: renovar, baseFhir: baseFhir)
      ..cliente = http;
    http.interceptors.add(testigo);
    return ClienteFhir(baseFhir: baseFhir, http: http);
  }

  /// La base FHIR del laboratorio.
  final Uri baseFhir;

  final Dio _http;

  /// Lee un recurso por su referencia relativa (`Patient/1234`).
  Future<Map<String, Object?>> leer(String referencia) async =>
      _pedir(() => _http.get<Map<String, Object?>>(referencia));

  /// Busca recursos de [tipo] y devuelve **todas** las entradas, siguiendo la paginación.
  ///
  /// La página siguiente se pide por `Bundle.link[relation=next]` y nunca construyendo la URL a
  /// mano: el servidor firma ahí su estado de búsqueda y un enlace inventado devuelve otra cosa.
  Future<List<Map<String, Object?>>> buscar(String tipo, Map<String, String> criterios) async {
    var bundle = await _pedir(
      () => _http.get<Map<String, Object?>>(tipo, queryParameters: criterios),
    );

    final encontrados = <Map<String, Object?>>[];
    var paginas = 0;
    while (paginas++ < _paginasComoMucho) {
      encontrados.addAll(_recursosDe(bundle));
      final siguiente = _enlace(bundle, 'next');
      if (siguiente == null) {
        break;
      }
      bundle = await _pedir(() => _http.getUri<Map<String, Object?>>(Uri.parse(siguiente)));
    }
    return encontrados;
  }

  /// Tope de páginas. Una persona con veinte años de analíticas no tiene que colgar la app, y una
  /// paginación mal contestada por el servidor no puede convertirse en un bucle infinito.
  static const int _paginasComoMucho = 20;

  static List<Map<String, Object?>> _recursosDe(Map<String, Object?> bundle) =>
      (bundle['entry'] as List<Object?>? ?? const [])
          .whereType<Map<String, Object?>>()
          .map((entrada) => entrada['resource'])
          .whereType<Map<String, Object?>>()
          .toList();

  static String? _enlace(Map<String, Object?> bundle, String relacion) =>
      (bundle['link'] as List<Object?>? ?? const [])
          .whereType<Map<String, Object?>>()
          .where((enlace) => enlace['relation'] == relacion)
          .map((enlace) => enlace['url'])
          .whereType<String>()
          .firstOrNull;

  Future<Map<String, Object?>> _pedir(
    Future<Response<Map<String, Object?>>> Function() llamada,
  ) async {
    try {
      return (await llamada()).data ?? const {};
    } on DioException catch (fallo) {
      throw _traducir(fallo);
    }
  }

  /// Convierte lo que devuelve el laboratorio en algo que se le puede decir a una persona.
  ///
  /// El `403` se cuenta con palabras propias y no con el `OperationOutcome` del servidor: el
  /// diagnóstico de ahí está escrito para quien administra el laboratorio —habla de recursos y de
  /// compartimentos— y quien está mirando la pantalla solo necesita saber que esa información no es
  /// suya. El recurso ajeno **no aparece por ningún lado**, que es lo que de verdad importa.
  static ErrorDeLaApp _traducir(DioException fallo) => switch (fallo.response?.statusCode) {
    null => ErrorDeLaApp.sinRed(),
    401 => ErrorDeLaApp.sesionCaducada(),
    403 => ErrorDeLaApp.sinPermiso(),
    404 => ErrorDeLaApp.laboratorio('El laboratorio no encuentra esa información.'),
    _ => ErrorDeLaApp.laboratorio(
      'El laboratorio ha respondido con un error. Inténtalo de nuevo más tarde.',
    ),
  };
}
