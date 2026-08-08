import 'package:flutter/foundation.dart';

/// Con qué datos se lanza el flujo SMART: quién es la app, adónde vuelve y qué pide.
///
/// Nada de esto son secretos. El cliente es **público** —una app que se descarga de una tienda no
/// puede guardar un secreto: lo lleva dentro cualquiera que la descomprima—, así que la seguridad
/// del canje la pone PKCE y no un `client_secret`.
@immutable
final class ConfiguracionSmart {
  /// Construye la configuración con todas sus piezas.
  const ConfiguracionSmart({
    required this.baseFhir,
    required this.clienteId,
    required this.redireccion,
    required this.esquemaDeRetorno,
  });

  /// La configuración con la que arranca la app, resolviendo las trampas de cada plataforma.
  ///
  /// La base FHIR se puede fijar al compilar con
  /// `--dart-define=HISPALIS_FHIR=https://laboratorio.example/fhir`. El valor por defecto es el de
  /// la pila de desarrollo, y **no es el mismo en el emulador de Android**: ahí `localhost` es el
  /// propio emulador y el equipo se alcanza por `10.0.2.2`. Es un fallo que solo aparece en el
  /// emulador y funciona en todas las demás plataformas, así que se resuelve aquí y no en la cabeza
  /// de quien lo arranque.
  factory ConfiguracionSmart.delEntorno() {
    const declarada = String.fromEnvironment(_variableDeEntorno);
    final base = declarada.isNotEmpty ? declarada : _basePorDefecto();

    return ConfiguracionSmart(
      baseFhir: Uri.parse(base),
      clienteId: clienteRegistrado,
      redireccion: Uri.parse(kIsWeb ? '${Uri.base.origin}$_paginaDeRetornoWeb' : _retornoNativo),
      esquemaDeRetorno: kIsWeb ? Uri.base.scheme : esquemaPropio,
    );
  }

  /// El identificador con el que la app está registrada en el realm `hispalis`.
  static const String clienteRegistrado = 'hispalis-app-ciudadano';

  /// El esquema propio por el que el navegador devuelve el control a la app en móvil.
  ///
  /// Va declarado en `AndroidManifest.xml` y en `Info.plist`: sin eso, el navegador se queda con la
  /// redirección y la app no se entera de nada.
  static const String esquemaPropio = 'es.hispalis.ciudadano';

  /// Lo que la app pide, y ni una letra más.
  ///
  /// - `openid` + `fhirUser` para saber **de quién** es esta app —el `fhirUser` es un
  ///   `Patient/<id>`, no un nombre de usuario—.
  /// - `launch/patient` para que el testigo venga con el paciente en contexto.
  /// - `patient/*.rs` para **leer y buscar** lo suyo. Nada de `.cruds`: esta app no escribe en la
  ///   historia de nadie, y pedir permiso de borrado «por si acaso» es exactamente lo que la norma
  ///   señala como el error de diseño más común.
  static const String scopes = 'openid fhirUser launch/patient patient/*.rs';

  static const String _variableDeEntorno = 'HISPALIS_FHIR';
  static const String _retornoNativo = '$esquemaPropio://callback';
  static const String _paginaDeRetornoWeb = '/auth.html';

  /// La base FHIR del laboratorio. Es también el `aud` que se pide en la autorización.
  final Uri baseFhir;

  /// El `client_id` registrado.
  final String clienteId;

  /// El `redirect_uri` exacto. Fijo y registrado: nunca se construye con datos de entrada.
  final Uri redireccion;

  /// El esquema por el que se reconoce la vuelta del navegador.
  final String esquemaDeRetorno;

  /// De dónde se baja `.well-known/smart-configuration`.
  Uri get descubrimiento => baseFhir.replace(
    pathSegments: [...baseFhir.pathSegments.where((s) => s.isNotEmpty), '.well-known', 'smart-configuration'],
  );

  static String _basePorDefecto() =>
      !kIsWeb && defaultTargetPlatform == TargetPlatform.android
      ? 'http://10.0.2.2:8080/fhir'
      : 'http://localhost:8080/fhir';
}
