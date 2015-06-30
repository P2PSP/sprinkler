# Funcionamiento de la app móvil

La aplicación móvil Sprinkler está altamente basada en el proyecto libre [**KickFlip**](https://github.com/Kickflip/kickflip-android-sdk), con licencia Apache V2. Esto quiere decir que siempre que se mantenga la autoría original es posible reutilizar esta librería.

Para hacer funcionar el proyecto, KickFlip ha tenido que ser modificado para enviar al servidor HTTP los datos de video en una petición HTTP Chunked en lugar de subirlos junto con su "playlist" a una instancia de Amazon AWS usando la propia API de KickFlip, que ha tenido que ser eliminada a lo largo de toda la librería para mayor comodidad. Aún así, es posible que en la versión modificada aún queden restos de ella que no estén referenciados por ninguna otra parte del código.

## Componentes de la librería KickFlip (modificada)

Los principales componentes de la librería son:

* Encoder: se encarga de recibir los datos de la cámara y pasarlos al formato de video correcto.
* Muxer: recoge los datos del encoder y los organiza de forma que puedan ser entendidos por otra aplicación.
* Broadcaster: envía los datos tras el muxing a su destino.

Estos son interfaces que tienen varias implementaciones reales detrás, de las cuales se acaba usando una u otra dependiendo de la situación.

En este caso, las clases que se usan son:

* CameraEncoder: gestiona la conexión con la cámara y el encoding de los datos recibidos.
* MicrophoneEncoder: igual que *CameraEncoder*, pero con el micrófono del dispositivo.
* FFmpegMuxer: recoge los datos de ambos encoders y los pasa por funciones del código de *FFMPEG* mediante NDK. En este caso, le pide que dados los streams de video y audio, haga muxing y cree los archivos necesarios para una transmisión mediante *HLS*. **Sin embargo, FFMPEG no los transmite**.
* Broadcaster: internamente contiene un observer que llama a un callback cuando se crea un nuevo archivo `.ts` en el directorio de grabación. Cuando esto ocurre, el *Broadcaster* lo añade a una cola y pone a subir el primer archivo de la cola hacia el servidor. Una vez subido, el archivo se elimina para no desperdiciar espacio en disco.

El **Broadcaster**, asimismo, lleva un control del ancho de banda disponible para el envío del video, de forma que si detecta que el ancho de banda disponible está muy cercano al necesario para enviar el video, se reduzca el bitrate del mismo, perdiendo calidad pero impidiendo ralentizaciones y cortes en la medida de lo posible.

Aparte de estas, la interfaz está implementada mediante una **BroadcastActivity** que contiene un **BroadcastFragment**, de forma que este fragment es reutilizable.

Todo se configura mediante una instancia *Singleton* del componente **SessionConfig**, que debe recrearse para cada nueva grabación.

## Posibles mejoras

En un futuro quizás sería posible prescindir del muxing a HLS en local y hacer muxing a FFM, el formato que FFServer necesita. Esto presenta varios problemas:

* No es seguro que el wrapper de FFMPEG utilizado aquí lo permita, aunque se podría compilar otra versión con soporte.
* No existe documentación clara de FFM salvo [su implementación en FFMPEG](http://git.videolan.org/?p=ffmpeg.git;a=blob_plain;f=libavformat/ffm.h;hb=HEAD).
* Al no existir dicha documentación, es difícil saber cuándo habría que transmitir nuevos datos, ya que el archivo `.ffm` no crece de forma desproporcionada, sino que su tamaño varía de forma dinámica.
* De esto último podría encargarse el propio FFMPEG del dispositivo, pero no tiene soporte para crear sus propios sockets o conexiones HTTP.
