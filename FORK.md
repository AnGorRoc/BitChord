# BitChord (fork de AnGorRoc)

Este fork parte del proyecto original [kushagrasinghx/BitChord](https://github.com/kushagrasinghx/BitChord) y lo sincroniza por versión. La rama de trabajo actual es `update/vX`, que se basa en el tag de upstream correspondiente. Este documento lista solo lo que el fork añade o cambia; el resto del comportamiento es el de upstream.

## Qué añade el fork

### Reproducción y audio
- **Fuente Hi-Res Lossless (Monochrome)**: audio FLAC real de hasta 24 bits. Está desactivada hasta que la activas en Ajustes.
- **Proveedores Lossless configurables** a mano, con failover por latencia.
- **Alineación de calidad en caliente**: al pasar de una versión con pérdida a FLAC durante la reproducción, las dos versiones se alinean a nivel de muestra antes del fundido.
- **Sin pérdida desde el inicio** (opcional): espera unos segundos a la versión FLAC antes de empezar.
- **Degradación automática** a calidad estándar si la red no sostiene FLAC, con reintentos progresivos.
- **Aviso en datos móviles** al reproducir sin pérdida.

### Descargas y caché
- **Planificador de descargas unificado** que prioriza la canción actual, la siguiente y el resto de la cola.
- **Caché de Lossless con presupuesto propio** (por defecto 1 GB), independiente de la caché estándar.
- **Niveles de retención Lossless**: desactivado, calidad CD o Hi-Res.
- **Mejora de canciones en caché**: si hay una versión de mayor calidad disponible, se reemplaza en segundo plano.
- **Descarga automática de playlists** dentro de la app, desactivada por defecto y con aviso silencioso.

### Enlaces y servicios externos
- **Enlaces de Spotify, Apple Music, Deezer, SoundCloud, Tidal y Amazon Music** se abren en BitChord buscando la canción por título y artista.
- **Spotify**: importación de biblioteca y playlists, sincronización incremental (solo busca las canciones nuevas), selección múltiple de listas y reintento de las que fallaron.
- **Exportación de playlists locales a YouTube Music**, enviadas en lotes con pausas para respetar los límites de la API.

### Interfaz
- **Biblioteca en cuadrícula o en filas**, con carátulas de 64 dp en las filas.
- **Precarga de carátulas** de las filas que vienen a continuación, para un desplazamiento sin huecos.
- **Miniatura desenfocada** mientras carga la carátula en alta resolución en la pantalla de reproducción.
- **Menú ⋮ y carátula del reproductor** rediseñados.
- **Animación de salida** al eliminar playlists de la biblioteca.
- **Traducción completa al español**, además de los idiomas existentes.

### Actualizaciones
- La comprobación de versión consulta las **releases de este fork**, no las de upstream.

## Otros documentos

Para el servidor de Listen Together y la traducción de letras, mira [ADDITIONAL.md](ADDITIONAL.md).
