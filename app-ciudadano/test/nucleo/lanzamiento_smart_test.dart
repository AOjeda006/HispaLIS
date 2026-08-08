import 'package:flutter_test/flutter_test.dart';
import 'package:hispalis_ciudadano/nucleo/errores/error_de_la_app.dart';
import 'package:hispalis_ciudadano/nucleo/seguridad/configuracion_smart.dart';
import 'package:hispalis_ciudadano/nucleo/seguridad/lanzamiento_smart.dart';
import 'package:hispalis_ciudadano/nucleo/seguridad/pkce.dart';
import 'package:hispalis_ciudadano/nucleo/seguridad/sesion.dart';

import '../ayudas/dobles.dart';

/// El laboratorio de mentira contra el que se prueba el flujo.
const String _base = 'http://laboratorio.test/fhir';
const String _emisor = 'http://identidad.test/realms/hispalis';

const String _caminoDescubrimiento = '/fhir/.well-known/smart-configuration';
const String _caminoTestigo = '/realms/hispalis/protocol/openid-connect/token';
const String _caminoOidc = '/realms/hispalis/.well-known/openid-configuration';
const String _caminoUserinfo = '/realms/hispalis/protocol/openid-connect/userinfo';

final ConfiguracionSmart _configuracion = ConfiguracionSmart(
  baseFhir: Uri.parse(_base),
  clienteId: ConfiguracionSmart.clienteRegistrado,
  redireccion: Uri.parse('es.hispalis.ciudadano://callback'),
  esquemaDeRetorno: ConfiguracionSmart.esquemaPropio,
);

Map<String, RespuestaPreparada> _servidorNormal({
  List<String> capacidades = const [
    'launch-standalone',
    'permission-patient',
    'client-public',
    'sso-openid-connect',
  ],
  List<String> metodos = const ['S256'],
  Map<String, Object?>? testigo,
}) => {
  _caminoDescubrimiento: RespuestaPreparada({
    'issuer': _emisor,
    'authorization_endpoint': '$_emisor/protocol/openid-connect/auth',
    'token_endpoint': '$_emisor/protocol/openid-connect/token',
    'capabilities': capacidades,
    'code_challenge_methods_supported': metodos,
  }),
  _caminoTestigo: RespuestaPreparada(
    testigo ??
        {
          'access_token': 'testigo-1',
          'token_type': 'Bearer',
          'expires_in': 300,
          'refresh_token': 'refresco-1',
          // El contexto de lanzamiento llega AQUÍ, como parámetro de la respuesta.
          'patient': 'paciente-1',
          'scope': 'openid fhirUser launch/patient patient/*.rs',
        },
  ),
  _caminoOidc: RespuestaPreparada({
    'issuer': _emisor,
    'userinfo_endpoint': '$_emisor/protocol/openid-connect/userinfo',
  }),
  _caminoUserinfo: RespuestaPreparada({'sub': 'abc', 'fhirUser': 'Patient/paciente-1'}),
};

/// Devuelve la vuelta que daría un servidor de identidad que autoriza.
Uri _vueltaBuena(Uri peticion) => Uri.parse(
  'es.hispalis.ciudadano://callback'
  '?code=codigo-1&state=${peticion.queryParameters['state']}',
);

void main() {
  group('Lanzamiento SMART autónomo', () {
    test('la petición de autorización lleva lo que la norma exige y ningún secreto', () async {
      final red = RedDeMentira(_servidorNormal());
      final navegador = NavegadorDeMentira(_vueltaBuena);
      final lanzamiento = LanzamientoSmart(
        configuracion: _configuracion,
        navegador: navegador,
        http: red.comoDio(),
      );

      await lanzamiento.entrar();

      final pedido = navegador.ultimaPeticion!.queryParameters;
      expect(pedido['response_type'], 'code');
      expect(pedido['client_id'], ConfiguracionSmart.clienteRegistrado);
      expect(pedido['redirect_uri'], 'es.hispalis.ciudadano://callback');
      expect(pedido['scope'], ConfiguracionSmart.scopes);
      // Sin `aud`, un testigo de este mismo emisor valdría contra otro servidor de recursos.
      expect(pedido['aud'], _base);
      expect(pedido['code_challenge_method'], 'S256');
      expect(pedido['code_challenge'], isNotEmpty);
      expect(pedido['state'], isNotNull);
      // Un cliente público no tiene secreto, y lo que no existe no se puede filtrar.
      expect(pedido.keys, isNot(contains('client_secret')));
    });

    test('el código se canjea con el verificador, y el verificador casa con el reto', () async {
      final red = RedDeMentira(_servidorNormal());
      final navegador = NavegadorDeMentira(_vueltaBuena);
      final lanzamiento = LanzamientoSmart(
        configuracion: _configuracion,
        navegador: navegador,
        http: red.comoDio(),
      );

      await lanzamiento.entrar();

      final canje = red.formularios.single;
      expect(canje['grant_type'], 'authorization_code');
      expect(canje['code'], 'codigo-1');
      expect(canje['client_id'], ConfiguracionSmart.clienteRegistrado);
      expect(canje['redirect_uri'], 'es.hispalis.ciudadano://callback');
      expect(canje.keys, isNot(contains('client_secret')));
      // Lo que de verdad prueba que PKCE está bien enchufado: el verificador que se manda al
      // canjear es el mismo del que salió el reto que se mandó al autorizar.
      expect(Pkce.reto(canje['code_verifier']!), navegador.ultimaPeticion!.queryParameters['code_challenge']);
    });

    test('el paciente en contexto sale de la respuesta del testigo, no de abrir el testigo', () async {
      final red = RedDeMentira(_servidorNormal());
      final lanzamiento = LanzamientoSmart(
        configuracion: _configuracion,
        navegador: NavegadorDeMentira(_vueltaBuena),
        http: red.comoDio(),
      );

      final sesion = await lanzamiento.entrar();

      expect(sesion.testigo, 'testigo-1');
      expect(sesion.paciente, 'paciente-1');
      expect(sesion.pacienteEnContexto, 'paciente-1');
      expect(sesion.fhirUser, 'Patient/paciente-1');
    });

    test('se guarda lo CONCEDIDO, que no tiene por qué ser lo pedido', () async {
      final red = RedDeMentira(
        _servidorNormal(
          testigo: {
            'access_token': 'testigo-1',
            'expires_in': 300,
            // El servidor recorta: da lectura pero no búsqueda.
            'scope': 'openid launch/patient patient/*.r',
            'patient': 'paciente-1',
          },
        ),
      );
      final lanzamiento = LanzamientoSmart(
        configuracion: _configuracion,
        navegador: NavegadorDeMentira(_vueltaBuena),
        http: red.comoDio(),
      );

      final sesion = await lanzamiento.entrar();

      expect(sesion.scopesConcedidos, {'openid', 'launch/patient', 'patient/*.r'});
      expect(sesion.scopesConcedidos, isNot(contains('fhirUser')));
    });

    test('una vuelta con otro `state` se tira sin canjear el código', () async {
      final red = RedDeMentira(_servidorNormal());
      final lanzamiento = LanzamientoSmart(
        configuracion: _configuracion,
        navegador: NavegadorDeMentira(
          (_) => Uri.parse('es.hispalis.ciudadano://callback?code=robado&state=otro'),
        ),
        http: red.comoDio(),
      );

      await expectLater(
        lanzamiento.entrar(),
        throwsA(
          isA<ErrorDeLaApp>().having(
            (e) => e.codigo,
            'codigo',
            CodigoDeError.autorizacionRechazada,
          ),
        ),
      );
      // Y lo importante: el código de esa respuesta NO se ha llegado a canjear.
      expect(red.formularios, isEmpty);
    });

    test('una vuelta con `error` se cuenta con lo que dijo el servidor', () async {
      final red = RedDeMentira(_servidorNormal());
      final lanzamiento = LanzamientoSmart(
        configuracion: _configuracion,
        navegador: NavegadorDeMentira(
          (peticion) => Uri.parse(
            'es.hispalis.ciudadano://callback?error=access_denied'
            '&error_description=No%20has%20autorizado%20la%20consulta'
            '&state=${peticion.queryParameters['state']}',
          ),
        ),
        http: red.comoDio(),
      );

      await expectLater(
        lanzamiento.entrar(),
        throwsA(
          isA<ErrorDeLaApp>().having((e) => e.mensaje, 'mensaje', 'No has autorizado la consulta'),
        ),
      );
      expect(red.formularios, isEmpty);
    });

    test('si el servidor no admite S256 no se manda a nadie a ninguna parte', () async {
      final red = RedDeMentira(_servidorNormal(metodos: ['plain']));
      final navegador = NavegadorDeMentira(_vueltaBuena);
      final lanzamiento = LanzamientoSmart(
        configuracion: _configuracion,
        navegador: navegador,
        http: red.comoDio(),
      );

      await expectLater(lanzamiento.entrar(), throwsA(isA<ErrorDeLaApp>()));
      // Nada de abrir el navegador para descubrir el problema a mitad del flujo.
      expect(navegador.ultimaPeticion, isNull);
    });

    test('si el servidor no ofrece lanzamiento autónomo, tampoco', () async {
      final red = RedDeMentira(_servidorNormal(capacidades: ['permission-patient']));
      final navegador = NavegadorDeMentira(_vueltaBuena);
      final lanzamiento = LanzamientoSmart(
        configuracion: _configuracion,
        navegador: navegador,
        http: red.comoDio(),
      );

      await expectLater(lanzamiento.entrar(), throwsA(isA<ErrorDeLaApp>()));
      expect(navegador.ultimaPeticion, isNull);
    });

    test('sin `sso-openid-connect` no se pregunta quién ha entrado', () async {
      final red = RedDeMentira(
        _servidorNormal(capacidades: ['launch-standalone', 'permission-patient']),
      );
      final lanzamiento = LanzamientoSmart(
        configuracion: _configuracion,
        navegador: NavegadorDeMentira(_vueltaBuena),
        http: red.comoDio(),
      );

      final sesion = await lanzamiento.entrar();

      expect(sesion.fhirUser, isNull);
      // El contexto de lanzamiento sigue bastando para saber de quién son los datos.
      expect(sesion.pacienteEnContexto, 'paciente-1');
      expect(red.peticiones.map((p) => p.uri.path), isNot(contains(_caminoUserinfo)));
    });
  });

  group('Renovación silenciosa', () {
    test('rota el testigo de refresco y no pide *scopes* nuevos', () async {
      final red = RedDeMentira({
        ..._servidorNormal(),
        _caminoTestigo: const RespuestaPreparada({
          'access_token': 'testigo-2',
          'expires_in': 300,
          'refresh_token': 'refresco-2',
        }),
      });
      final lanzamiento = LanzamientoSmart(
        configuracion: _configuracion,
        navegador: NavegadorDeMentira(_vueltaBuena),
        http: red.comoDio(),
      );
      final vieja = DatosDeSesion(
        testigo: 'testigo-1',
        caducaEn: DateTime.now(),
        scopesConcedidos: const {'patient/*.rs'},
        testigoDeRefresco: 'refresco-1',
        paciente: 'paciente-1',
      );

      final nueva = await lanzamiento.renovar(vieja);

      expect(nueva.testigo, 'testigo-2');
      expect(nueva.testigoDeRefresco, 'refresco-2');
      expect(nueva.paciente, 'paciente-1');
      expect(nueva.scopesConcedidos, {'patient/*.rs'});
      expect(nueva.caducaEn.isAfter(DateTime.now()), isTrue);

      final canje = red.formularios.single;
      expect(canje['grant_type'], 'refresh_token');
      expect(canje['refresh_token'], 'refresco-1');
      // Ni `scope` de más ni `offline_access`: una app con el móvil en un cajón no lee historias.
      expect(canje.keys, isNot(contains('scope')));
      expect(canje.keys, isNot(contains('client_secret')));
    });

    test('sin testigo de refresco, la sesión está caducada y punto', () async {
      final red = RedDeMentira(_servidorNormal());
      final lanzamiento = LanzamientoSmart(
        configuracion: _configuracion,
        navegador: NavegadorDeMentira(_vueltaBuena),
        http: red.comoDio(),
      );

      await expectLater(
        lanzamiento.renovar(
          DatosDeSesion(
            testigo: 'testigo-1',
            caducaEn: DateTime.now(),
            scopesConcedidos: const {},
          ),
        ),
        throwsA(
          isA<ErrorDeLaApp>().having((e) => e.codigo, 'codigo', CodigoDeError.sesionCaducada),
        ),
      );
      expect(red.peticiones, isEmpty);
    });
  });
}
