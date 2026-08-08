import 'package:flutter_test/flutter_test.dart';
import 'package:hispalis_ciudadano/nucleo/seguridad/pkce.dart';

void main() {
  group('PKCE', () {
    test('el reto es el del vector del apéndice B de la RFC 7636', () {
      // El único vector oficial que existe. Si esto pasa, el S256 está bien hecho: resumen SHA-256,
      // base64url **sin relleno** y sobre los bytes ASCII del verificador, no sobre su base64.
      const verificador = 'dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk';
      const reto = 'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM';

      expect(Pkce.reto(verificador), reto);
    });

    test('el reto no lleva relleno ni caracteres fuera de base64url', () {
      final reto = Pkce.reto(Pkce.verificador());

      expect(reto, isNot(contains('=')));
      expect(reto, matches(RegExp(r'^[A-Za-z0-9\-_]+$')));
    });

    test('el verificador cumple la longitud que exige la RFC', () {
      final verificador = Pkce.verificador();

      // 43..128 caracteres. Con 32 bytes de azar en base64url sin relleno salen 43 justos.
      expect(verificador.length, greaterThanOrEqualTo(43));
      expect(verificador.length, lessThanOrEqualTo(128));
      expect(verificador, matches(RegExp(r'^[A-Za-z0-9\-_]+$')));
    });

    test('dos verificadores seguidos no se repiten', () {
      final unos = List.generate(50, (_) => Pkce.verificador()).toSet();

      expect(unos, hasLength(50));
    });

    test('el estado tiene entropía de sobra para lo que la norma pide', () {
      final estado = Pkce.estado();

      // SMART exige al menos 122 bits. 43 caracteres base64url son 256 bits.
      expect(estado.length, greaterThanOrEqualTo(43));
      expect(List.generate(50, (_) => Pkce.estado()).toSet(), hasLength(50));
    });
  });
}
