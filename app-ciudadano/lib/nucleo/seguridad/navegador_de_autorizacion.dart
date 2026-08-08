import 'package:flutter/services.dart';
import 'package:flutter_web_auth_2/flutter_web_auth_2.dart';

import '../errores/error_de_la_app.dart';

/// Quien lleva a la persona al servidor de identidad y trae de vuelta la respuesta.
///
/// Está detrás de un puerto por dos razones. La de prueba es evidente: un test no abre un
/// navegador. La otra es que **este es el único sitio de la app por el que pasa una credencial de
/// usuario**, y tenerlo con nombre propio deja claro que la app nunca ve la contraseña: la teclea la
/// persona en el navegador del sistema, sobre el dominio del servidor de identidad, con la barra de
/// direcciones a la vista.
abstract interface class NavegadorDeAutorizacion {
  /// Abre [peticion] y espera la vuelta por [esquemaDeRetorno].
  ///
  /// Devuelve la URL de retorno completa. Lanza [ErrorDeLaApp] si la persona cancela.
  Future<Uri> pedirAutorizacion({required Uri peticion, required String esquemaDeRetorno});
}

/// El navegador del sistema: pestaña de autenticación en Android, `ASWebAuthenticationSession` en
/// iOS, ventana aparte en web.
///
/// **Nunca un `WebView` empotrado.** Un `WebView` dentro de la app puede leer lo que la persona
/// teclea y no enseña de quién es el dominio donde lo está tecleando, que son justo las dos cosas
/// que hacen creíble una pantalla de identificación.
final class NavegadorDelSistema implements NavegadorDeAutorizacion {
  /// Construye el navegador.
  const NavegadorDelSistema();

  @override
  Future<Uri> pedirAutorizacion({required Uri peticion, required String esquemaDeRetorno}) async {
    try {
      final vuelta = await FlutterWebAuth2.authenticate(
        url: peticion.toString(),
        callbackUrlScheme: esquemaDeRetorno,
        // Sesión efímera: la pantalla de identificación no hereda las cookies del navegador ni se
        // las deja puestas al salir. En un dispositivo compartido, lo contrario significa que el
        // siguiente que abra la app entra como el anterior.
        options: const FlutterWebAuth2Options(preferEphemeral: true),
      );
      return Uri.parse(vuelta);
    } on PlatformException catch (fallo) {
      throw ErrorDeLaApp.autorizacionRechazada(
        fallo.code == 'CANCELED'
            ? 'Has cancelado la identificación. Sin ella no se pueden consultar tus resultados.'
            : 'No se ha podido completar la identificación. Inténtalo de nuevo.',
      );
    }
  }
}
