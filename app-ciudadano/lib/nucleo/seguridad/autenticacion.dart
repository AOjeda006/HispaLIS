import '../errores/error_de_la_app.dart';
import 'lanzamiento_smart.dart';
import 'sesion.dart';

/// Entrar, renovar y salir. Es lo único que el resto de la app sabe de la identidad.
///
/// Existe para que ni las pantallas ni el cliente FHIR tengan que conocer el flujo de autorización:
/// la pantalla de entrada llama a [entrar], el interceptor llama a [renovar] y el botón de cerrar
/// sesión llama a [salir].
final class Autenticacion {
  /// Construye el servicio sobre la sesión y el flujo de lanzamiento.
  const Autenticacion({required Sesion sesion, required LanzamientoSmart lanzamiento})
    : _sesion = sesion,
      _lanzamiento = lanzamiento;

  final Sesion _sesion;
  final LanzamientoSmart _lanzamiento;

  /// Recorre el lanzamiento SMART y deja la sesión abierta. Propaga [ErrorDeLaApp] si no se puede.
  Future<void> entrar() async => _sesion.abrir(await _lanzamiento.entrar());

  /// Intenta renovar la sesión en silencio. Devuelve si lo consiguió.
  ///
  /// **No lanza.** Quien la llama es el interceptor, en medio de una petición que ya tiene su propio
  /// error que contar: si la renovación falla, lo que la persona verá es el `401` traducido a
  /// «tu sesión ha caducado», que es exactamente lo que ha pasado.
  ///
  /// Al fallar se cierra la sesión, y con ella se borra el almacén: un testigo de refresco que el
  /// servidor ya ha rechazado no se guarda «por si acaso».
  Future<bool> renovar() async {
    final actual = _sesion.datos;
    if (actual == null) {
      return false;
    }
    try {
      await _sesion.abrir(await _lanzamiento.renovar(actual));
      return true;
    } on ErrorDeLaApp {
      await _sesion.cerrar();
      return false;
    }
  }

  /// Cierra la sesión y borra todo lo guardado en el dispositivo.
  Future<void> salir() => _sesion.cerrar();
}
