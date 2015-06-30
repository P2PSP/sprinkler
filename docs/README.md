# Introducción

## Componentes del sistema

El sistema está compuesto por las siguientes aplicaciones:

* App móvil: graba y hace muxing inicial a *HTTP Live Streaming* (HLS), usando H264 y AAC en formato `MPEG-TS` internamente. Se comunica con el servidor.
* App servidor: al iniciarse crea una instancia de un proceso **FFServer** para que se ejecute mientras se ejecute el servidor. Al recibir una petición de emisión desde la app móvil instancia otro proceso, este de **FFMPEG**, al que le transmitirá los datos de video recibidos mediante la petición HTTP.
* Instancia de FFMPEG: recibe los datos en formato `MPEG-TS` del servidor, hace un muxing al formato `.ffm` y lo envía al proceso *FFServer* para que pueda retransmitirlo.
* Instancia de FFServer: recibe los datos en `.ffm` enviados por el proceso *FFMPEG* en uno de sus **Feeds** y los manipula y reenvía por sus **Streams** a las aplicaciones clientes.

## Requisitos de uso:

* Sistema Windows o UNIX con:
    * **Python 2.7** para lanzar el servidor.
    * **FFMPEG y FFServer 2.6.3** o superior con soporte para **libvpx y libvorbis** en caso de querer usar el encoding a **webM**. Es posible que funcione en versiones anteriores, pero se ha comprobado que funciona con la antes mencionada.
* Dispositivo con **Android 4.3** o superior. La aplicación móvil hace uso de APIs de encoding de video que fueron introducidas en dicha versión.

## Uso de la aplicación **Sprinkler**

*Nota: cuando se habla de video, generalmente se hace referencia a video+audio*.

La pantalla principal de la aplicación permite configurar la conexión al servidor, con las siguientes opciones:

* **Protocolo**: permite especificar entre HTTP y HTTPS.
* **Dominio o IP**: dirección del servidor.
* **Puerto**: puerto en el que escucha el servidor.
* **Feed**: nombre del feed de *FFServer*.

Con estos datos se realizaría una petición HTTP chunked *-una petición POST cuyo contenido en el body no son unos datos con tamaño fijo sino un stream continuo de datos de tamaño desconocido-* con el siguiente formato:

`[protocolo]://[dominio|ip]:[puerto]/emit?channel=[feed]`

El servidor está preparado para recibir y tratar los datos que se envíen a este tipo de direcciones.

### Configuración del stream de video

Si bien es cierto que gran parte de la responsabilidad sobre la calidad del video recae en la configuración de *FFServer*, hay un componente inicial que no depende de él, sino de la app. Estos datos también son configurables, teniendo:

* **Resolución**: resolución inicial del vídeo, es posible escoger entre las que soporta la cámara del dispositivo. Hay que tener en cuenta que si la resolución inicial es menor que la seleccionada en *FFServer* la imagen se pixelará y estirará.
* **Video Bitrate**: cantidad de datos de video (en Kbps) que enviará como máximo la app. Esto afecta enormemente en la calidad del video, ya que si un video tiene mucha resolución pero bajo bitrate la calidad de imagen tendrá que disminuir para poder enviar toda la información disponible en un "contenedor" tan pequeño.
* **Audio Bitrate**: igual que el *Video Bitrate*, pero en el stream de audio.
* **Duración del segmento .ts**: *HTTP Live Streaming -* o **HLS** para abreviar, hace un muxing u organización de los datos de video en ficheros `.ts` con contenido en `MPEG-TS` de una duración determinada. Hay que tener en cuenta que la duración es directamente proporcional al *delay* que presentará el video al transmitirse, ya que una duración de 1s implica que habría al menos un segundo de grabación de diferencia entre lo que se ve y lo que se grabó (sin contar los envíos, demuxing, muxing, decoding y encoding hechos por el resto del sistema). Por lo tanto, cuanto más corto el segmento, menor será el delay.
