/// El único tipo de error que llega a la interfaz, y lo que se le dice a la persona.
///
/// La app habla con un laboratorio y con un servidor de identidad, así que puede fallar de muchas
/// maneras. Ninguna de ellas se enseña como viene: un `DioException`, un `403` o una traza son
/// información para quien programa, no para quien está mirando sus análisis en el móvil.
library;

/// Qué ha fallado, en las categorías que cambian lo que la app hace después.
enum CodigoDeError {
  /// No hay forma de llegar al laboratorio: sin cobertura, servidor caído, DNS.
  sinRed,

  /// El testigo ha caducado o el laboratorio lo ha rechazado. Hay que volver a entrar.
  sesionCaducada,

  /// El laboratorio dice que esos datos no son suyos. **No es un fallo de la app.**
  sinPermiso,

  /// La persona canceló la pantalla de identificación, o el servidor de identidad dijo que no.
  autorizacionRechazada,

  /// El laboratorio contestó algo que la app no esperaba.
  laboratorio,
}

/// Un fallo con un mensaje en español listo para enseñar.
final class ErrorDeLaApp implements Exception {
  /// Construye el error con su categoría y su mensaje.
  const ErrorDeLaApp(this.codigo, this.mensaje);

  /// El laboratorio no contesta.
  factory ErrorDeLaApp.sinRed() => const ErrorDeLaApp(
    CodigoDeError.sinRed,
    'No se ha podido contactar con el laboratorio. Comprueba tu conexión e inténtalo de nuevo.',
  );

  /// La sesión ya no vale y hay que identificarse otra vez.
  factory ErrorDeLaApp.sesionCaducada() => const ErrorDeLaApp(
    CodigoDeError.sesionCaducada,
    'Tu sesión ha caducado. Vuelve a identificarte para seguir consultando tus resultados.',
  );

  /// El laboratorio ha negado el acceso a ese dato concreto.
  ///
  /// Es el caso que la norma SMART avisa de que hay que manejar: **tener el permiso no garantiza
  /// los datos**. Quien decide de quién es cada resultado es el laboratorio, no esta app ni el
  /// servidor de identidad, así que este mensaje no puede prometer que reintentar sirva de algo.
  factory ErrorDeLaApp.sinPermiso() => const ErrorDeLaApp(
    CodigoDeError.sinPermiso,
    'El laboratorio no te permite consultar esta información. Solo puedes ver tus propios '
        'resultados.',
  );

  /// La identificación no se completó.
  factory ErrorDeLaApp.autorizacionRechazada(String detalle) =>
      ErrorDeLaApp(CodigoDeError.autorizacionRechazada, detalle);

  /// El laboratorio contestó un error que la app no sabe interpretar mejor.
  factory ErrorDeLaApp.laboratorio(String detalle) =>
      ErrorDeLaApp(CodigoDeError.laboratorio, detalle);

  /// La categoría del fallo.
  final CodigoDeError codigo;

  /// Lo que se le enseña a la persona, en español y sin jerga.
  final String mensaje;

  /// Si hace falta volver a pasar por la pantalla de identificación.
  bool get exigeVolverAEntrar => codigo == CodigoDeError.sesionCaducada;

  @override
  String toString() => mensaje;
}
