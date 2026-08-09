# El receptor de notificaciones: el sistema al que el laboratorio entrega, simulado.
#
# Se construye desde la raíz del repositorio:
#   docker build -f simuladores/Dockerfile -t hispalis-receptor .
#
# No lleva `HISPALIS_CLAVE_HIS` por defecto y no es un descuido: sin clave compartida el receptor
# NO arranca, porque aceptar una notificación sin firma es aceptarla de cualquiera. Un valor por
# defecto en un fichero versionado es la forma habitual de que un secreto acabe en producción.

FROM python:3.13-slim

WORKDIR /simuladores

# Sin `pip install`: el receptor no tiene ni una dependencia fuera de la biblioteca estándar, y meter
# un instalador por costumbre añadiría al contenedor todo lo que el generador sí necesita.
COPY simuladores/receptor ./receptor
ENV PYTHONPATH=/simuladores \
    PYTHONUNBUFFERED=1

# Corre sin privilegios: es un servidor HTTP expuesto en la red del compose.
RUN useradd --system --uid 10003 receptor
USER 10003

EXPOSE 8090

ENTRYPOINT ["python", "-m", "receptor"]
