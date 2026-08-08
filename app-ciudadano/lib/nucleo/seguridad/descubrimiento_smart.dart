import 'package:flutter/foundation.dart';

/// Lo que el laboratorio publica en `.well-known/smart-configuration`.
///
/// **Nada se cablea.** Los dos *endpoints* del flujo salen de aquí, y las capacidades se leen antes
/// de suponer nada: la norma dice que el servidor sostiene lo que declara y puede sostener más, así
/// que esta lista es el mínimo garantizado y no el catálogo.
@immutable
final class DescubrimientoSmart {
  /// Construye el documento ya interpretado.
  const DescubrimientoSmart({
    required this.autorizacion,
    required this.testigo,
    required this.capacidades,
    required this.metodosDeReto,
    this.emisor,
  });

  /// Interpreta el JSON del descubrimiento.
  ///
  /// Las URL del documento **deben** ser absolutas; si alguna llega relativa —servidor no
  /// conforme— se resuelve contra [baseFhir] en vez de reventar. Es la única concesión: caerse
  /// porque un servidor escribió `/oauth/token` no ayuda a nadie.
  factory DescubrimientoSmart.deJson(Map<String, Object?> json, Uri baseFhir) {
    Uri absoluta(String campo) {
      final valor = json[campo];
      if (valor is! String || valor.isEmpty) {
        throw const FormatException(
          'El descubrimiento SMART del laboratorio no dice dónde autorizar ni dónde canjear.',
        );
      }
      return baseFhir.resolve(valor);
    }

    final emisor = json['issuer'];
    return DescubrimientoSmart(
      autorizacion: absoluta('authorization_endpoint'),
      testigo: absoluta('token_endpoint'),
      capacidades: _cadenas(json['capabilities']),
      metodosDeReto: _cadenas(json['code_challenge_methods_supported']),
      emisor: emisor is String && emisor.isNotEmpty ? Uri.parse('$emisor/') : null,
    );
  }

  /// A dónde se manda a la persona a identificarse.
  final Uri autorizacion;

  /// El emisor OIDC, del que cuelga su propio descubrimiento. Ausente si el servidor no hace SSO.
  ///
  /// Se guarda con la barra final para que `resolve` no se coma el último segmento del camino: el
  /// emisor de un realm de Keycloak es `…/realms/hispalis`, y sin la barra
  /// `resolve('.well-known/…')` produce `…/realms/.well-known/…`.
  final Uri? emisor;

  /// Dónde se canjea el código por el testigo.
  final Uri testigo;

  /// Lo que el servidor declara que sabe hacer.
  final Set<String> capacidades;

  /// Los métodos de reto PKCE que admite.
  final Set<String> metodosDeReto;

  /// Si admite `S256`. Es lo único aceptable: `plain` manda el verificador en claro.
  bool get admiteS256 => metodosDeReto.contains('S256');

  /// Si admite el lanzamiento autónomo, que es el que hace esta app.
  bool get admiteLanzamientoAutonomo => capacidades.contains('launch-standalone');

  /// Si admite los *scopes* `patient/`, sin los cuales esta app no tiene nada que pedir.
  bool get admitePermisosDePaciente => capacidades.contains('permission-patient');

  static Set<String> _cadenas(Object? valor) =>
      valor is List ? valor.whereType<String>().toSet() : const <String>{};
}
