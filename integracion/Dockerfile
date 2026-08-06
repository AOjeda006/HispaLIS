# Imagen del motor de integración: canales HL7 V2.5.1 sobre MLLP.
#
# Mismo montaje que la del backend —contexto de construcción en la RAÍZ del repositorio, de ahí que
# las rutas lleven `integracion/`— para que el `compose` comparta contexto entre las tres imágenes.

FROM eclipse-temurin:21-jdk AS construccion

WORKDIR /construccion

# Wrapper y POM primero: el árbol de HAPI son cientos de artefactos y no cambia casi nunca, mientras
# que los canales cambian en cada commit.
COPY integracion/.mvn .mvn
COPY integracion/mvnw integracion/pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline

COPY integracion/src src
# Sin tests: levantan PostgreSQL y un servidor HTTP, y son cosa de la CI. Que la imagen construya no
# dice nada sobre si los canales funcionan; eso ya lo dijo `./mvnw verify` antes de llegar aquí.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests \
    && cp target/hispalis-integracion-*.jar aplicacion.jar

FROM eclipse-temurin:21-jre

# Nada corre como root si no hace falta.
RUN useradd --system --create-home --uid 10002 hispalis
USER hispalis

WORKDIR /opt/hispalis
COPY --from=construccion /construccion/aplicacion.jar aplicacion.jar

# El puerto MLLP. No es HTTP y no lo atiende ningún proxy: el plano de sistemas va directo y cifrado
# de extremo a extremo (D4), así que meterlo detrás de nginx solo terminaría el TLS antes de tiempo.
EXPOSE 2575

# El almacén de claves NO viaja en la imagen: se monta como volumen y su contraseña llega por
# variable de entorno. Una imagen con la clave privada dentro es una clave publicada en cuanto la
# imagen se comparte.
ENTRYPOINT ["java", "-jar", "/opt/hispalis/aplicacion.jar"]
