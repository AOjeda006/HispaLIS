import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';

import '../../dominio/informe.dart';
import '../../nucleo/seguridad/autenticacion.dart';
import '../formato.dart';
import '../rutas.dart';
import '../widgets/aviso_de_error.dart';
import 'informes_vm.dart';

/// La lista de analíticas de la persona, de la más reciente a la más antigua.
///
/// Cada tarjeta dice ya desde la lista si la analítica está validada o no. Quien entra a ver un
/// resultado no debería descubrir dentro que aún no lo ha revisado nadie.
class PantallaDeInformes extends StatefulWidget {
  /// Construye la pantalla.
  const PantallaDeInformes({super.key});

  @override
  State<PantallaDeInformes> createState() => _PantallaDeInformesState();
}

class _PantallaDeInformesState extends State<PantallaDeInformes> {
  @override
  void initState() {
    super.initState();
    // Después del primer fotograma: `cargar` notifica, y notificar durante el `build` es un error.
    WidgetsBinding.instance.addPostFrameCallback((_) => context.read<InformesVm>().cargar());
  }

  @override
  Widget build(BuildContext context) {
    final vm = context.watch<InformesVm>();

    return Scaffold(
      appBar: AppBar(
        title: const Text('Mis resultados'),
        actions: [
          IconButton(
            onPressed: _salir,
            icon: const Icon(Icons.logout),
            tooltip: 'Cerrar sesión',
          ),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: context.read<InformesVm>().cargar,
        child: switch (vm) {
          InformesVm(cargando: true) => const Center(child: CircularProgressIndicator()),
          InformesVm(error: final fallo?) => AvisoDeError(
            error: fallo,
            reintentar: () => context.read<InformesVm>().cargar(),
          ),
          InformesVm(vacio: true) => const _NadaTodavia(),
          _ => _Lista(vm: vm),
        },
      ),
    );
  }

  /// Cierra la sesión y borra de memoria lo que se hubiera cargado.
  ///
  /// El orden importa: primero se olvida lo clínico y después se borran las credenciales. Al
  /// desaparecer la sesión, el enrutador lleva sola a la pantalla de entrada.
  Future<void> _salir() async {
    context.read<InformesVm>().olvidar();
    await context.read<Autenticacion>().salir();
  }
}

class _Lista extends StatelessWidget {
  const _Lista({required this.vm});

  final InformesVm vm;

  @override
  Widget build(BuildContext context) {
    final paciente = vm.paciente;

    return ListView.builder(
      // Siempre desplazable, para que se pueda tirar a recargar aunque quepa todo en pantalla.
      physics: const AlwaysScrollableScrollPhysics(),
      padding: const EdgeInsets.only(bottom: 24),
      itemCount: vm.informes.length + 1,
      itemBuilder: (context, indice) {
        if (indice == 0) {
          return paciente == null ? const SizedBox.shrink() : _Encabezado(nombre: paciente.nombreCompleto);
        }
        return _TarjetaDeInforme(informe: vm.informes[indice - 1]);
      },
    );
  }
}

class _Encabezado extends StatelessWidget {
  const _Encabezado({required this.nombre});

  /// El nombre completo, con **los apellidos enteros**. Nunca se parte por el espacio.
  final String nombre;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
    child: Text(nombre, style: Theme.of(context).textTheme.titleLarge),
  );
}

class _TarjetaDeInforme extends StatelessWidget {
  const _TarjetaDeInforme({required this.informe});

  final Informe informe;

  @override
  Widget build(BuildContext context) {
    final colores = Theme.of(context).colorScheme;
    final validado = informe.validadoPorFacultativo;

    return Card(
      color: colores.surfaceContainerHighest,
      child: ListTile(
        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        title: Text(informe.titulo),
        subtitle: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const SizedBox(height: 4),
            Text('${Formato.fecha(informe.fecha)} · ${_cuantos(informe)}'),
            const SizedBox(height: 6),
            Row(
              children: [
                Icon(
                  validado ? Icons.verified_outlined : Icons.schedule_outlined,
                  size: 16,
                  color: validado ? colores.primary : colores.tertiary,
                ),
                const SizedBox(width: 4),
                Flexible(
                  child: Text(
                    validado ? 'Validada por el facultativo' : 'Pendiente de validar',
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: validado ? colores.primary : colores.tertiary,
                    ),
                  ),
                ),
              ],
            ),
          ],
        ),
        trailing: const Icon(Icons.chevron_right),
        onTap: () => context.go(Rutas.informe(informe.id)),
      ),
    );
  }

  static String _cuantos(Informe informe) =>
      informe.resultados.length == 1 ? '1 determinación' : '${informe.resultados.length} determinaciones';
}

class _NadaTodavia extends StatelessWidget {
  const _NadaTodavia();

  @override
  Widget build(BuildContext context) => ListView(
    physics: const AlwaysScrollableScrollPhysics(),
    children: [
      Padding(
        padding: const EdgeInsets.fromLTRB(24, 96, 24, 24),
        child: Column(
          children: [
            Icon(
              Icons.science_outlined,
              size: 48,
              color: Theme.of(context).colorScheme.outline,
            ),
            const SizedBox(height: 16),
            Text(
              'Todavía no hay resultados tuyos en este laboratorio.',
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.bodyLarge,
            ),
          ],
        ),
      ),
    ],
  );
}
