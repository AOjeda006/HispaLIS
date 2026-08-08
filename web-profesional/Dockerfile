# Imagen de la web del profesional: se construye con Node y se sirve con nginx.
#
# El contexto de construcción es la RAÍZ del repositorio, compartido con las otras dos imágenes.
#
# Esta imagen **ya no compila la guía**. Hasta el ítem 33 sí lo hacía: copiaba `ig/`, corría SUSHI y
# metía el `CodeSystem` del catálogo dentro del paquete, para que la pantalla de alta ofreciera las
# pruebas de la guía y no una lista escrita en TypeScript (D15). Cumplía el invariante y congelaba el
# catálogo en el momento del build. Ahora la web se lo pregunta al servidor de terminología con
# `$expand`, así que el invariante se cumple igual, en tiempo de ejecución, y esta imagen se queda
# sin SUSHI, sin Java y sin `ig/`.

FROM node:24-alpine AS construccion

WORKDIR /construccion

# Las dependencias en su propia capa, que es lo que casi nunca cambia.
COPY web-profesional/package.json web-profesional/package-lock.json web-profesional/
RUN cd web-profesional && npm ci

COPY web-profesional web-profesional
RUN cd web-profesional && npm run build

FROM nginx:alpine

COPY web-profesional/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=construccion /construccion/web-profesional/dist/web-profesional/browser /usr/share/nginx/html

EXPOSE 80
