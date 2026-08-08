import 'dart:convert';
import 'dart:math';

import 'package:crypto/crypto.dart';

/// PKCE (RFC 7636) y el `state` del flujo de autorización.
///
/// Los dos existen por la misma razón y contra ataques distintos. El **verificador** impide que
/// quien intercepte el código de autorización pueda canjearlo: sin el secreto que solo tiene esta
/// app, el código no vale nada. El **`state`** impide que a esta app le cuelen la respuesta de una
/// autorización que no pidió.
///
/// Los dos salen del generador **criptográfico** del sistema. `Random()` a secas es predecible a
/// partir de unas cuantas salidas, y un `state` predecible no defiende de nada.
abstract final class Pkce {
  /// Cuántos bytes de azar. 32 bytes = 256 bits, muy por encima de los 122 que exige SMART, y
  /// dentro de los 43–128 caracteres que RFC 7636 admite para el verificador.
  static const int _bytesDeAzar = 32;

  static final Random _azar = Random.secure();

  /// Un verificador PKCE nuevo: 256 bits en `base64url` sin relleno.
  static String verificador() => _aleatorio();

  /// Un `state` nuevo, con la misma entropía y por el mismo motivo.
  static String estado() => _aleatorio();

  /// El reto `S256` que le corresponde a [verificador].
  ///
  /// Es el SHA-256 del verificador en `base64url` **sin relleno**. El relleno (`=`) sobra a
  /// propósito: la norma lo prohíbe y algunos servidores rechazan el reto entero por él.
  ///
  /// Nunca se usa `plain`. Mandar el verificador tal cual por el canal que se quiere proteger
  /// convierte PKCE en un trámite que no protege de nada.
  static String reto(String verificador) => _base64Url(sha256.convert(utf8.encode(verificador)).bytes);

  static String _aleatorio() =>
      _base64Url(List<int>.generate(_bytesDeAzar, (_) => _azar.nextInt(256)));

  /// `base64url` sin relleno, que es lo que RFC 7636 llama *base64url-encode*.
  static String _base64Url(List<int> bytes) => base64UrlEncode(bytes).replaceAll('=', '');
}
