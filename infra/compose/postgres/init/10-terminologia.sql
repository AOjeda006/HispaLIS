-- El servidor de terminología tiene su propia base, no un esquema dentro de la del laboratorio.
--
-- Es un servicio de otro: lo gobierna su propio Hibernate, con su `ddl-auto`, y comparte instancia
-- con el laboratorio solo porque esto es una pila de desarrollo. Con un esquema dentro de `hispalis`
-- bastaría un `hbm2ddl` despistado para tocar las tablas del laboratorio, y sobre todo no se podría
-- llevar el servidor a otra máquina —o cambiarlo por Snowstorm— sin desenredar antes las tablas.
--
-- ⚠️ PostgreSQL solo ejecuta esto en la PRIMERA inicialización del volumen. Sobre una pila que ya
-- existía hay que borrar el volumen (`docker compose down -v`) o crear la base a mano.
CREATE DATABASE terminologia OWNER hispalis;
