import 'package:flutter_test/flutter_test.dart';
import 'package:hispalis_ciudadano/nucleo/seguridad/autenticacion.dart';
import 'package:hispalis_ciudadano/nucleo/seguridad/configuracion_smart.dart';
import 'package:hispalis_ciudadano/nucleo/seguridad/lanzamiento_smart.dart';
import 'package:hispalis_ciudadano/nucleo/seguridad/sesion.dart';

import '../ayudas/dobles.dart';

/// La renovación silenciosa, que es la mitad de `Autenticacion` que tiene reglas.
///
/// La cobertura la señaló con un cero redondo: `LanzamientoSmart` está probado a conciencia y
/// `Sesion` también, pero la pieza que decide **qué hacer cuando la renovación falla** no la tocaba
/// ningún test. Y esa decisión es de seguridad, no de comodidad: un testigo de refresco que el
/// servidor de identidad ya ha rechazado no se guarda «por si acaso», porque lo que queda en el
/// dispositivo es una credencial muerta que alguien podría intentar reutilizar.
const String _base = 'http://laboratorio.test/fhir';
const String _emisor = 'http://identidad.test/realms/hispalis';

const String _caminoDescubrimiento = '/fhir/.well-known/smart-configuration';
const String _caminoTestigo = '/realms/hispalis/protocol/openid-connect/token';

final ConfiguracionSmart _configuracion = ConfiguracionSmart(
  baseFhir: Uri.parse(_base),
  clienteId: ConfiguracionSmart.clienteRegistrado,
  redireccion: Uri.parse('es.hispalis.ciudadano://callback'),
  esquemaDeRetorno: ConfiguracionSmart.esquemaPropio,
);

Map<String, RespuestaPreparada> _servidorQue(RespuestaPreparada alCanjear) => {
  _caminoDescubrimiento: RespuestaPreparada({
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
  _caminoTestigo: alCanjear,
};

DatosDeSesion _sesionAbierta({String? refresco = 'refresco-1'}) =>
    DatosDeSesion(
      testigo: 'testigo-viejo',
      caducaEn: DateTime.now().add(const Duration(seconds: 30)),
      scopesConcedidos: const {'patient/*.rs'},
      testigoDeRefresco: refresco,
      paciente: 'paciente-1',
    );

Autenticacion _autenticacionCon(Sesion sesion, RedDeMentira red) =>
    Autenticacion(
      sesion: sesion,
      lanzamiento: LanzamientoSmart(
        configuracion: _configuracion,
        navegador: NavegadorDeMentira((peticion) => peticion),
        http: red.comoDio(),
      ),
    );

void main() {
  group('Renovación de la sesión', () {
    test(
      'la renovación en silencio deja el testigo nuevo y conserva el contexto',
      () async {
        final almacen = AlmacenDeMentira();
        final sesion = Sesion(almacen);
        await sesion.abrir(_sesionAbierta());
        final red = RedDeMentira(
          _servidorQue(
            const RespuestaPreparada({
              'access_token': 'testigo-nuevo',
              'token_type': 'Bearer',
              'expires_in': 300,
              'refresh_token': 'refresco-2',
            }),
          ),
        );

        final renovada = await _autenticacionCon(sesion, red).renovar();

        expect(renovada, isTrue);
        expect(sesion.datos!.testigo, 'testigo-nuevo');
        // El de refresco es de un solo uso: el nuevo sustituye al anterior.
        expect(sesion.datos!.testigoDeRefresco, 'refresco-2');
        // Y el contexto de lanzamiento no se pierde al renovar: sin él, la app no sabría de quién
        // son los informes que va a pedir a continuación.
        expect(sesion.datos!.pacienteEnContexto, 'paciente-1');
      },
    );

    test(
      'si el servidor rechaza el refresco, no queda nada guardado en el dispositivo',
      () async {
        final almacen = AlmacenDeMentira();
        final sesion = Sesion(almacen);
        await sesion.abrir(_sesionAbierta());
        final red = RedDeMentira(
          _servidorQue(
            const RespuestaPreparada({'error': 'invalid_grant'}, estado: 400),
          ),
        );

        final renovada = await _autenticacionCon(sesion, red).renovar();

        expect(renovada, isFalse);
        expect(sesion.datos, isNull);
        expect(
          almacen.contenido,
          isEmpty,
          reason:
              'una credencial que el servidor ya ha rechazado no se guarda por si acaso',
        );
      },
    );

    test(
      'sin sesión abierta no se intenta renovar ni se sale a la red',
      () async {
        final red = RedDeMentira(_servidorQue(const RespuestaPreparada({})));

        final renovada = await _autenticacionCon(
          Sesion(AlmacenDeMentira()),
          red,
        ).renovar();

        expect(renovada, isFalse);
        // Ni una llamada: quien no tiene sesión no tiene nada que renovar, y preguntarlo sería
        // contarle al servidor de identidad que esta app está abierta.
        expect(red.peticiones, isEmpty);
      },
    );

    test(
      'una sesión sin testigo de refresco se cierra en vez de arrastrarse',
      () async {
        final almacen = AlmacenDeMentira();
        final sesion = Sesion(almacen);
        await sesion.abrir(_sesionAbierta(refresco: null));
        final red = RedDeMentira(_servidorQue(const RespuestaPreparada({})));

        final renovada = await _autenticacionCon(sesion, red).renovar();

        expect(renovada, isFalse);
        expect(sesion.datos, isNull);
        expect(almacen.contenido, isEmpty);
      },
    );

    test('salir borra la sesión y el almacén entero', () async {
      final almacen = AlmacenDeMentira()..contenido['otra.cosa'] = 'lo que sea';
      final sesion = Sesion(almacen);
      await sesion.abrir(_sesionAbierta());
      final red = RedDeMentira(_servidorQue(const RespuestaPreparada({})));

      await _autenticacionCon(sesion, red).salir();

      expect(sesion.datos, isNull);
      expect(almacen.contenido, isEmpty);
    });
  });
}
