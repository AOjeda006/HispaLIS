# Imagen de la web del profesional: se construye con Node y se sirve con nginx.
#
# El contexto de construcción es la RAÍZ del repositorio porque esta imagen necesita `ig/`: el
# catálogo de pruebas que ofrece la pantalla de alta sale del `CodeSystem` que publica la guía (D15)
# y no de una lista escrita en TypeScript.

FROM node:24-alpine AS construccion

WORKDIR /construccion

# La terminología se COMPILA aquí, no se copia de la máquina de quien construye: `ig/fsh-generated/`
# está en el `.dockerignore` a propósito. Así construir desde un clon recién hecho y construir desde
# un clon con la guía ya compilada dan exactamente lo mismo.
COPY ig ig
RUN cd ig && npx --yes fsh-sushi .

# Las dependencias en su propia capa, que es lo que casi nunca cambia.
COPY web-profesional/package.json web-profesional/package-lock.json web-profesional/
RUN cd web-profesional && npm ci

COPY web-profesional web-profesional
# `prebuild` trae el catálogo de `ig/fsh-generated/` a `public/`; si faltara, el build para y lo dice.
RUN cd web-profesional && npm run build

FROM nginx:alpine

COPY web-profesional/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=construccion /construccion/web-profesional/dist/web-profesional/browser /usr/share/nginx/html

EXPOSE 80
