# Funcionamiento del servidor

El servidor consta de varios componentes:

* Archivo `server.py`: es el servidor propiamente dicho, recibe las peticiones y el video de los dispositivos de grabación e iniciliza al resto de componentes.
* Instancia única de FFServer: al iniciar el servidor, se crea esta instancia del proceso `ffserver` con la configuración del archivo `ffserver.conf` de la misma carpeta.
* Múltiples instancias de FFMPEG: con cada nueva conexión de un feed se crea una instancia de FFMPEG para configurar el video.

## Servidor

Al inicializarse, crea:

* Un servidor HTTP multihilo para responder a las peticiones de los dispositivos de grabación. Cada petición se atenderá en un hilo aparte.
* La instancia única del proceso FFServer.

Cuando le llega una nueva conexión, el servidor comprueba que no se esté emitiendo ya a dicho feed y asigna el nombre del feed a un nuevo proceso FFMPEG creado.

Este proceso se encargará de convertir los datos de video que se le pasen al formato FFM que necesita FFServer y se le enviará a dicho proceso para que lo sirva.

A su vez, se inicia un bucle en el que se leen los chunks de video que se van subiendo en la petición POST chunked. Los datos tienen la siguiente forma:

```
---- CHUNK ----
480\r\n
........................
\r\n

---- CHUNK ----
736\r\n
........................
\r\n
```

* Una "cabecera" que contiene el tamaño del payload de video y un salto de línea de sistemas Windows (retorno de carro y salto de línea) `\r\n`.
* El payload.
* Otro salto de línea con retorno de carro para indicar el fin del payload y el inicio de un nuevo chunk.

El proceso por tanto es:

1. Se lee el chunk de datos MPEG-TS en el server.py.
2. Se le pasa a la instancia de FFMPEG asignada mediante un socket efímero TCP.
3. FFMPEG convierte de MPEG-TS a FFM.
4. FFMPEG envía los datos convertidos a FFServer mediante HTTP.
5. FFServer recibe los datos y los convierte a sus respectivos formatos de streaming.
6. Al conectarse un cliente a un stream, se le envían los datos de video correctamente formateados mediante HTTP.

## Posibles problemas y mejoras

* El tener que usar un socket TCP para cada conexión `server.py` -> FFMPEG hace que se desperdicien puertos en la máquina. Aunque es probable que dada una cantidad muy alta de clientes la máquina servidora se quede antes sin ancho de banda o capacidad de procesamiento que sin puertos, es un factor a tener en cuenta.
* FFServer es estático. Hay que conocer de antes los feeds y configurar los streams. Sería interesante poder crear feeds sobre la marcha con configuraciones de stream genéricas, pero no parece estar soportado.
* El demuxing de MPEG-TS y posterior muxing a FFM consume tiempo y recursos, aumentando el delay y restringiendo la cantidad de feeds y streams que puede gestionar una máquina.
