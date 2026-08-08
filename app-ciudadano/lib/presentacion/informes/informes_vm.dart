import 'package:flutter/foundation.dart';

import '../../dominio/informe.dart';
import '../../dominio/paciente.dart';
import '../../dominio/repositorio_de_informes.dart';
import '../../nucleo/errores/error_de_la_app.dart';
import '../../nucleo/seguridad/sesion.dart';

/// La lógica de la pantalla de resultados. Los widgets solo la miran.
final class InformesVm extends ChangeNotifier {
  /// Construye el modelo sobre el repositorio y la sesión abierta.
  InformesVm({required RepositorioDeInformes repositorio, required Sesion sesion})
    : _repositorio = repositorio,
      _sesion = sesion;

  final RepositorioDeInformes _repositorio;
  final Sesion _sesion;

  bool _cargando = false;
  ErrorDeLaApp? _error;
  Paciente? _paciente;
  List<Informe> _informes = const [];

  /// Si hay una carga en marcha.
  bool get cargando => _cargando;

  /// El último fallo, o `null`.
  ErrorDeLaApp? get error => _error;

  /// La persona cuya pantalla se está enseñando.
  Paciente? get paciente => _paciente;

  /// Sus analíticas, de la más reciente a la más antigua.
  List<Informe> get informes => _informes;

  /// Si el laboratorio no tiene nada suyo todavía.
  bool get vacio => !_cargando && _error == null && _informes.isEmpty;

  /// Trae el historial de quien tiene la sesión abierta.
  ///
  /// El paciente sale del **contexto del testigo**, no de un campo que se teclee: esta app no tiene
  /// una pantalla donde buscar a otra persona, y esa ausencia es parte del diseño. Aun así, quien
  /// decide de quién son los datos es el laboratorio — el contexto solo evita pedir a ciegas.
  Future<void> cargar() async {
    final paciente = _sesion.datos?.pacienteEnContexto;
    if (paciente == null) {
      _error = ErrorDeLaApp.laboratorio(
        'Tu identificación no está vinculada a ninguna historia de este laboratorio. '
        'Ponte en contacto con el laboratorio para activarla.',
      );
      notifyListeners();
      return;
    }

    _cargando = true;
    _error = null;
    notifyListeners();

    try {
      final historial = await _repositorio.historial(paciente);
      _paciente = historial.paciente;
      _informes = historial.informes;
    } on ErrorDeLaApp catch (fallo) {
      _error = fallo;
      _paciente = null;
      _informes = const [];
    } finally {
      _cargando = false;
      notifyListeners();
    }
  }

  /// El informe con ese id, o `null` si no está cargado.
  Informe? porId(String id) => _informes.where((informe) => informe.id == id).firstOrNull;

  /// Olvida lo cargado. Se llama al cerrar sesión: nada clínico se queda en memoria.
  void olvidar() {
    _paciente = null;
    _informes = const [];
    _error = null;
    notifyListeners();
  }
}
