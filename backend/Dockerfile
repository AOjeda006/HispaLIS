# Imagen del backend: dominio + API FHIR R5 + proyección.
#
# El contexto de construcción es la RAÍZ del repositorio, no `backend/`, porque el `compose` comparte
# contexto con la imagen de la web —que sí necesita `ig/`—. De ahí que las rutas lleven `backend/`.

FROM eclipse-temurin:21-jdk AS construccion

WORKDIR /construccion

# El wrapper y el POM primero, y las dependencias en su propia capa: el árbol de HAPI son cientos de
# artefactos y no cambia casi nunca, mientras que el código cambia en cada commit.
COPY backend/.mvn .mvn
COPY backend/mvnw backend/pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline

COPY backend/src src
# Sin tests a propósito: los tests levantan su propio PostgreSQL y son cosa de la CI, no de construir
# la imagen. Que el build de la imagen pase no dice nada sobre si el código funciona; eso ya lo dijo
# `./mvnw verify` antes de llegar aquí.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests \
    && cp target/hispalis-backend-*.jar aplicacion.jar

FROM eclipse-temurin:21-jre

# `curl` es lo que permite que el `compose` sepa cuándo el backend está listo de verdad. Sin él, la
# web arrancaría antes y devolvería 502 a las primeras peticiones.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Nada corre como root si no hace falta.
RUN useradd --system --create-home --uid 10001 hispalis
USER hispalis

WORKDIR /opt/hispalis
COPY --from=construccion /construccion/aplicacion.jar aplicacion.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/opt/hispalis/aplicacion.jar"]
