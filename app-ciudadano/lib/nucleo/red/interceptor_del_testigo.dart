import 'package:dio/dio.dart';

import '../seguridad/lanzamiento_smart.dart';
import '../seguridad/sesion.dart';

/// Renueva la sesión y devuelve si lo consiguió.
typedef RenovadorDeSesion = Future<bool> Function();

/// Pone el testigo en las llamadas al laboratorio, y **solo** en esas.
///
/// El filtro por destino no es celo: un `Bundle` puede traer enlaces a otros servidores, y seguir
/// uno mandando el testigo es entregárselo a un tercero. La regla es la de la norma: si un recurso
/// apunta fuera, o se ignora o se pide autorización aparte.
///
/// Es también donde vive la renovación silenciosa. La alternativa —mandar a la persona otra vez a
/// la pantalla de identificación cada quince minutos— hace que se pierda lo que estuviera mirando
/// por un motivo que no es suyo.
final class InterceptorDelTestigo extends Interceptor {
  /// Construye el interceptor.
  InterceptorDelTestigo({
    required Sesion sesion,
    required RenovadorDeSesion renovar,
    required Uri baseFhir,
  }) : _sesion = sesion,
       _renovar = renovar,
       _baseFhir = baseFhir;

  /// Marca que un reintento ya se hizo, para no entrar en bucle contra un `401` permanente.
  static const String _yaSeReintento = 'hispalis.reintentado';

  final Sesion _sesion;
  final RenovadorDeSesion _renovar;
  final Uri _baseFhir;

  /// El cliente con el que reintentar. Lo pone quien construye el cliente FHIR.
  late final Dio cliente;

  @override
  Future<void> onRequest(RequestOptions options, RequestInterceptorHandler handler) async {
    if (!_esDelLaboratorio(options.uri)) {
      return handler.next(options);
    }

    var datos = _sesion.datos;
    if (datos != null && datos.caducadaCon(LanzamientoSmart.margenDeCaducidad)) {
      await _renovar();
      datos = _sesion.datos;
    }
    if (datos != null) {
      options.headers['Authorization'] = 'Bearer ${datos.testigo}';
    }
    return handler.next(options);
  }

  @override
  Future<void> onError(DioException err, ErrorInterceptorHandler handler) async {
    final peticion = err.requestOptions;
    final renovable =
        err.response?.statusCode == 401 &&
        peticion.extra[_yaSeReintento] != true &&
        _esDelLaboratorio(peticion.uri);

    if (!renovable || !await _renovar()) {
      return handler.next(err);
    }

    peticion.extra[_yaSeReintento] = true;
    try {
      handler.resolve(await cliente.fetch<Object?>(peticion));
    } on DioException catch (segundoIntento) {
      handler.next(segundoIntento);
    }
  }

  /// Si la petición va al laboratorio para el que se pidió el testigo.
  ///
  /// Se compara el origen **y** el camino base: dos aplicaciones distintas en el mismo servidor no
  /// son el mismo destinatario.
  bool _esDelLaboratorio(Uri destino) =>
      destino.scheme == _baseFhir.scheme &&
      destino.host == _baseFhir.host &&
      destino.port == _baseFhir.port &&
      destino.path.startsWith(_baseFhir.path);
}
