# Fucionamiento general del sistema

El sistema está compuesto por 3 elementos principales:

* **Dispositivo de grabación**: graba y envía video al servidor.
* **Servidor**: trata y prepara el video para su distribución.
* **Cliente**: recibe y reproduce el video.

El siguiente esquema esboza el funcionamiento del sistema:

![Esquema](img/Workflow.svg)

1. El dispositivo graba video (H264) y audio (AAC) con los encoders.
2. El video y audio se mezcla y organiza en el `FFmpegMuxer` al formato MPEG-TS apto para enviar video por internet mediante FFMPEG, que crea pequeños archivos `.ts`.
3. Cuando un archivo `.ts` es creado por FFMPEG, el `Broadcaster` lo detecta y lo sube al servidor mediante una POST HTTP chunked request.
4. El servidor inicia una instancia única de FFServer al iniciar.
5. Al conectar desde el dispositivo de grabación, el servidor crea una nueva instancia de FFMPEG.
6. El servidor procesa el video que va llegando en cada chunk.
7. Cuando ha leído un chunk, el servidor lo pasa a la instancia creada de FFMPEG.
8. Este FFMPEG hace demuxing de MPEG-TS y muxing a formato FFM.
9. Una vez hecho el remux del chunk, se envía al feed correcto del FFServer.
10. FFServer lee los datos de los feeds y hace re-encoding de estos a los formatos indicados en los *Streams* de su archivo de configuración `ffserver.conf`.
11. Cuando se conecta un cliente a FFServer, este empieza a server un feed con su configuración de stream correspondiente en tiempo real.

## Principales problemas

* **Necesidad de Android 4.3 para la grabación**: al usar clases de la API de Android 4.3 para la captura y procesado de video, es necesario que el dispositivo de grabación tenga una versión del SO mayor o igual a esta. Por este funcionamiento, además, es imposible hacer grabación de video en segundo plano. No parece haber una solución fácil a este problema.
* **Delay**: al usar HTTP Live Streaming para generar los archivos .ts existe el problema de que el archivo debe generarse antes de enviarse, teniendo un delay inicial igual al tiempo de grabación del archivo. A este delay hay que sumarle todos los demuxing, muxing, decoding y encoding a lo largo del sistema así como el tiempo de transmisión, lo cual puede dar lugar a un delay mínimo de 4-5s.

*Delay = tiempo_grabacion + ∑tiempos_transmision + ∑tiempos_tratamiento_video*

* **Errores de conexión si hay un cliente conectado**: se ha observado que en ciertas ocasiones, cuando hay un cliente aún conectado a un Stream de FFMPEG (que ya no emite) y se vuelven a empezar a transmitir los datos del Feed, la conexión con el servidor se pierde en el envío de los 2 primeros chunks. Probablemente sea un error de FFServer.
* **Posibles errores de threading en dispositivo**: el hilo de grabación de la cámara está en segundo plano con NDK y tiene que contactar frecuentemente con con otro hilo en primer plano mediante un *Handler*. Es posible que en algún momento uno de los 2 hilos haya sido cancelado y el Handler envíe un mensaje a un Thread que ya no funciona, ocasionando errores en la aplicación. Aunque en principio el error no se ha vuelto a observer desde que se implementó un bugfix, es posible que no esté solucionado del todo.
* **Posibles errores de threading en servidor**: al cerrar la aplicación o una conexión, el hilo encargado de dicha tarea debería cerrarse también. Aunque el servidor está implementado para que esto sea así, no se ha hecho un testeo exhaustivo que permita comprobar que no existen estos problemas en casos extremos.
