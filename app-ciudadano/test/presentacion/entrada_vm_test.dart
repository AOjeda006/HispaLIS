import 'package:flutter_test/flutter_test.dart';
import 'package:hispalis_ciudadano/nucleo/seguridad/autenticacion.dart';
import 'package:hispalis_ciudadano/nucleo/seguridad/configuracion_smart.dart';
import 'package:hispalis_ciudadano/nucleo/seguridad/lanzamiento_smart.dart';
import 'package:hispalis_ciudadano/nucleo/seguridad/sesion.dart';
import 'package:hispalis_ciudadano/presentacion/entrada/entrada_vm.dart';

import '../ayudas/dobles.dart';

/// El modelo de la pantalla de entrada.
///
/// Otro cero de la cobertura, y este duele más porque es donde empieza todo: si el flujo falla y el
/// modelo se queda con `trabajando` en cierto, la pantalla se queda girando para siempre y la única
/// salida es matar la app. Que el botón vuelva es tan parte del comportamiento como que el mensaje
/// se cuente en español.
const String _base = 'http://laboratorio.test/fhir';
const String _emisor = 'http://identidad.test/realms/hispalis';

final ConfiguracionSmart _configuracion = ConfiguracionSmart(
  baseFhir: Uri.parse(_base),
  clienteId: ConfiguracionSmart.clienteRegistrado,
  redireccion: Uri.parse('es.hispalis.ciudadano://callback'),
  esquemaDeRetorno: ConfiguracionSmart.esquemaPropio,
);

final Map<String, RespuestaPreparada> _servidorNormal = {
  '/fhir/.well-known/smart-configuration': RespuestaPreparada({
    'issuer': _emisor,
    'authorization_endpoint': '$_emisor/protocol/openid-connect/auth',
    'token_endpoint': '$_emisor/protocol/openid-connect/token',
    'capabilities': const [
      'launch-standalone',
      'permission-patient',
      'client-public',
    ],
    'code_challenge_methods_supported': const ['S256'],
  }),
};

EntradaVm _modeloQueCancela() => EntradaVm(
  Autenticacion(
    sesion: Sesion(AlmacenDeMentira()),
    lanzamiento: LanzamientoSmart(
      configuracion: _configuracion,
      navegador: const NavegadorQueCancela(),
      http: RedDeMentira(_servidorNormal).comoDio(),
    ),
  ),
);

void main() {
  group('Pantalla de entrada', () {
    test('cuando la identificación falla, se cuenta y el botón vuelve', () async {
      final modelo = _modeloQueCancela();

      await modelo.entrar();

      expect(modelo.error, isNotNull);
      expect(modelo.error!.mensaje, 'Has cancelado la identificación.');
      expect(
        modelo.trabajando,
        isFalse,
        reason:
            'con `trabajando` en cierto la pantalla se queda girando y no hay forma de reintentar',
      );
    });

    test('avisa dos veces: al empezar y al terminar', () async {
      final modelo = _modeloQueCancela();
      var avisos = 0;
      modelo.addListener(() => avisos++);

      await modelo.entrar();

      // Uno para pintar el indicador y otro para quitarlo. Sin el primero, el botón no reacciona al
      // tocarlo y la persona lo pulsa otra vez.
      expect(avisos, 2);
    });

    test(
      'un intento nuevo limpia el error del anterior antes de empezar',
      () async {
        final modelo = _modeloQueCancela();
        await modelo.entrar();
        expect(modelo.error, isNotNull);

        // El error se limpia al arrancar el intento, no al acabarlo: si se dejara para el final, la
        // pantalla enseñaría el fallo viejo mientras el nuevo intento está en marcha.
        final segundoIntento = modelo.entrar();
        expect(modelo.error, isNull);
        await segundoIntento;
      },
    );
  });
}
