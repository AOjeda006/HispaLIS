import 'package:dio/dio.dart';
import 'package:get_it/get_it.dart';
import 'package:go_router/go_router.dart';

import '../../datos/repositorio_de_informes_fhir.dart';
import '../../dominio/repositorio_de_informes.dart';
import '../../presentacion/entrada/entrada_vm.dart';
import '../../presentacion/informes/informes_vm.dart';
import '../../presentacion/rutas.dart';
import '../almacen/almacen_seguro.dart';
import '../red/cliente_fhir.dart';
import '../seguridad/autenticacion.dart';
import '../seguridad/configuracion_smart.dart';
import '../seguridad/lanzamiento_smart.dart';
import '../seguridad/navegador_de_autorizacion.dart';
import '../seguridad/sesion.dart';

/// Dónde se enchufa cada pieza con cada pieza. El único sitio que conoce el grafo entero.
///
/// Está aquí y no repartido en constructores por dos razones concretas: los tests sustituyen una
/// pieza sin tocar las demás, y se ve de un vistazo que **hay dos clientes HTTP distintos**. Esa
/// separación no es una elegancia: el cliente que habla con el servidor de identidad no lleva el
/// interceptor del testigo, porque mandarle al servidor de identidad el testigo del laboratorio
/// sería entregárselo a quien no tiene por qué verlo.
abstract final class Contenedor {
  /// El registro de dependencias de la app.
  static final GetIt _piezas = GetIt.instance;

  /// Monta el grafo y recupera la sesión que hubiera guardada.
  ///
  /// Se llama una sola vez, antes de `runApp`. Recuperar la sesión aquí evita el parpadeo de enseñar
  /// la pantalla de entrada a quien ya estaba dentro.
  static Future<void> montar({ConfiguracionSmart? configuracion, AlmacenSeguro? almacen}) async {
    final ajustes = configuracion ?? ConfiguracionSmart.delEntorno();

    _piezas
      ..registerSingleton<ConfiguracionSmart>(ajustes)
      ..registerSingleton<AlmacenSeguro>(almacen ?? const AlmacenDeLaPlataforma())
      ..registerSingleton<Sesion>(Sesion(_piezas<AlmacenSeguro>()))
      ..registerLazySingleton<NavegadorDeAutorizacion>(NavegadorDelSistema.new)
      ..registerLazySingleton<LanzamientoSmart>(
        () => LanzamientoSmart(
          configuracion: ajustes,
          navegador: _piezas<NavegadorDeAutorizacion>(),
          // Cliente limpio: sin interceptor y sin cabecera de autorización por defecto.
          http: Dio(BaseOptions(connectTimeout: const Duration(seconds: 10))),
        ),
      )
      ..registerLazySingleton<Autenticacion>(
        () => Autenticacion(
          sesion: _piezas<Sesion>(),
          lanzamiento: _piezas<LanzamientoSmart>(),
        ),
      )
      ..registerLazySingleton<ClienteFhir>(
        () => ClienteFhir.paraElLaboratorio(
          baseFhir: ajustes.baseFhir,
          sesion: _piezas<Sesion>(),
          renovar: () => _piezas<Autenticacion>().renovar(),
        ),
      )
      ..registerLazySingleton<RepositorioDeInformes>(
        () => RepositorioDeInformesFhir(_piezas<ClienteFhir>()),
      )
      ..registerLazySingleton<EntradaVm>(() => EntradaVm(_piezas<Autenticacion>()))
      ..registerLazySingleton<InformesVm>(
        () => InformesVm(
          repositorio: _piezas<RepositorioDeInformes>(),
          sesion: _piezas<Sesion>(),
        ),
      )
      ..registerLazySingleton<GoRouter>(() => Rutas.enrutador(_piezas<Sesion>()));

    await _piezas<Sesion>().recordar();
  }

  /// Resuelve una pieza registrada.
  static T dame<T extends Object>() => _piezas<T>();

  /// Desmonta el grafo. Lo usan los tests entre casos.
  static Future<void> desmontar() => _piezas.reset();
}
