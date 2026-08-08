import '../dominio/informe.dart';
import '../dominio/repositorio_de_informes.dart';
import '../dominio/resultado.dart';
import '../nucleo/red/cliente_fhir.dart';
import 'fhir/mapeadores.dart';

/// El historial de la persona, leído de la API FHIR del laboratorio.
///
/// **Sin datos falsos en ninguna parte.** Todo lo que se enseña sale de la API; no hay un solo
/// recurso de ejemplo dentro de la app.
final class RepositorioDeInformesFhir implements RepositorioDeInformes {
  /// Construye el repositorio sobre el cliente FHIR.
  const RepositorioDeInformesFhir(this._laboratorio);

  final ClienteFhir _laboratorio;

  @override
  Future<Historial> historial(String pacienteId) async {
    final paciente = MapeadorFhir.paciente(await _laboratorio.leer('Patient/$pacienteId'));
    final referencia = 'Patient/$pacienteId';

    // Las dos búsquedas, en paralelo: son independientes y la pantalla las necesita juntas.
    final [observaciones, informes] = await Future.wait([
      _laboratorio.buscar('Observation', {'patient': referencia, '_sort': '-date'}),
      _laboratorio.buscar('DiagnosticReport', {'patient': referencia, '_sort': '-issued'}),
    ]);

    final resultados = {
      for (final recurso in observaciones)
        'Observation/${recurso['id']}': MapeadorFhir.resultado(recurso, paciente.sexo),
    };

    final emitidos = informes
        .map((recurso) => MapeadorFhir.informe(recurso, resultados))
        .toList();

    return Historial(
      paciente: paciente,
      informes: [...emitidos, ...?_loQueSigueEnCurso(resultados, emitidos)]
        ..sort((a, b) => b.fecha.compareTo(a.fecha)),
    );
  }

  /// Agrupa lo que el analizador ya ha medido y ningún facultativo ha firmado todavía.
  ///
  /// No es un adorno: sin esto, a la persona le faltarían de la pantalla resultados que **existen**
  /// en el laboratorio y que puede consultar, y no tendría forma de saber que su analítica está en
  /// marcha. Se enseña aparte y con su aviso, porque una cifra sin revisar puede cambiar.
  ///
  /// Lo anulado no entra: un resultado retirado por el laboratorio no está «en curso», está fuera.
  static List<Informe>? _loQueSigueEnCurso(
    Map<String, Resultado> resultados,
    List<Informe> emitidos,
  ) {
    final yaInformados = {
      for (final informe in emitidos)
        for (final resultado in informe.resultados) resultado.id,
    };

    final sueltos = resultados.values
        .where((r) => !yaInformados.contains(r.id))
        .where((r) => r.estado != EstadoDelResultado.anulado)
        .toList();

    if (sueltos.isEmpty) {
      return null;
    }

    final fechas = sueltos.map((r) => r.medidoEn).whereType<DateTime>();
    return [
      Informe.enCurso(
        fecha: fechas.isEmpty
            ? DateTime.now()
            : fechas.reduce((a, b) => a.isAfter(b) ? a : b),
        resultados: sueltos,
      ),
    ];
  }
}
