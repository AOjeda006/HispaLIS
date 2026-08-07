# El cargador del servidor de terminología: corre una vez, carga y termina.
#
# Se construye desde la raíz del repositorio:
#   docker build -f terminologia/Dockerfile -t hispalis-cargador-terminologia .

FROM python:3.13-slim

WORKDIR /cargador

COPY terminologia/pyproject.toml ./
COPY terminologia/cargador ./cargador
RUN pip install --no-cache-dir .

# Ni la guía compilada ni las releases entran en la imagen: se montan. La terminología licenciada no
# se copia dentro de un contenedor que alguien pueda publicar en un registro.
ENV HISPALIS_GUIA=/guia \
    HISPALIS_LOINC=/releases/loinc/loinc-2.82 \
    HISPALIS_THO=/releases/hl7-fhir/igs/hl7-terminology-7.3.0/paquetes/hl7.terminology.r5.tgz

ENTRYPOINT ["python", "-m", "cargador"]
