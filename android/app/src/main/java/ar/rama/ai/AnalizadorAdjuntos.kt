package ar.rama.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Un archivo que el usuario adjuntó, ya analizado. */
data class Adjunto(
    val nombre: String,
    val tipo: String,
    val mime: String,
    val tamanioBytes: Long,
    val miniatura: Bitmap?,
    val datos: List<Pair<String, String>>,
    val resumen: String,
    val pasos: List<String>,
)

/**
 * Analiza fotos, PDFs y videos con lo que trae el propio Android.
 *
 * Importante y dicho sin vueltas: esto lee metadatos y píxeles, no entiende
 * el contenido. Rama sabe cuánto mide tu foto y de qué color predomina; no
 * sabe qué hay retratado en ella.
 */
object AnalizadorAdjuntos {

    private const val LADO_MINIATURA = 512

    fun analizar(contexto: Context, uri: Uri): Adjunto {
        val mime = contexto.contentResolver.getType(uri) ?: "application/octet-stream"
        val (nombre, tamanio) = nombreYTamanio(contexto, uri)
        return when {
            mime.startsWith("image/") -> analizarImagen(contexto, uri, nombre, mime, tamanio)
            mime.startsWith("video/") -> analizarVideo(contexto, uri, nombre, mime, tamanio)
            mime == "application/pdf" || nombre.lowercase().endsWith(".pdf") ->
                analizarPdf(contexto, uri, nombre, mime, tamanio)
            else -> Adjunto(
                nombre, "archivo", mime, tamanio, null,
                listOf("Tipo" to mime, "Tamaño" to pesoLegible(tamanio)),
                "Recibí «$nombre» ($mime, ${pesoLegible(tamanio)}). De este formato sólo puedo " +
                    "leerte el nombre y el tamaño: sé abrir imágenes, PDFs y videos.",
                listOf("Tipo MIME no reconocido: $mime", "Sin analizador específico para este formato"),
            )
        }
    }

    // ------------------------------------------------------------ imagen

    private fun analizarImagen(
        contexto: Context, uri: Uri, nombre: String, mime: String, tamanio: Long,
    ): Adjunto {
        val pasos = mutableListOf<String>()
        val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contexto.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, limites) }
        val ancho = limites.outWidth
        val alto = limites.outHeight
        pasos.add("Leí el encabezado: ${ancho}x${alto} px, formato ${limites.outMimeType ?: mime}")

        val miniatura = decodificarEscalada(contexto, uri, ancho, alto)
        pasos.add("Decodifiqué una miniatura de ${miniatura?.width ?: 0}x${miniatura?.height ?: 0} px")

        val datos = mutableListOf(
            "Dimensiones" to "$ancho x $alto px",
            "Megapíxeles" to String.format(Locale.US, "%.1f MP", ancho.toLong() * alto / 1_000_000.0),
            "Proporción" to proporcion(ancho, alto),
            "Formato" to (limites.outMimeType ?: mime),
            "Tamaño" to pesoLegible(tamanio),
        )

        val tono = miniatura?.let { colorPredominante(it) }
        if (tono != null) {
            datos.add("Color medio" to tono.primero)
            datos.add("Luminosidad" to tono.segundo)
            pasos.add("Promedié los píxeles de la miniatura: ${tono.primero}, ${tono.segundo}")
        }

        val exif = leerExif(contexto, uri)
        if (exif.isNotEmpty()) {
            datos.addAll(exif)
            pasos.add("Encontré metadatos EXIF: ${exif.joinToString(", ") { it.first }}")
        } else {
            pasos.add("La imagen no trae metadatos EXIF (o fueron borrados al compartirla)")
        }

        val orientacion = when {
            ancho > alto -> "horizontal"
            alto > ancho -> "vertical"
            else -> "cuadrada"
        }
        val resumen = buildString {
            append("Es una imagen $orientacion de $ancho x $alto px")
            if (tono != null) append(", donde predomina ${tono.primero.lowercase()}")
            append(". Pesa ${pesoLegible(tamanio)}.")
            val camara = exif.firstOrNull { it.first == "Cámara" }?.second
            if (camara != null) append(" Según el EXIF la sacó una $camara.")
            append("\n\nOjo: leo el archivo, no entiendo la escena. Puedo decirte cómo es la foto, no qué hay en ella.")
        }
        return Adjunto(nombre, "imagen", mime, tamanio, miniatura, datos, resumen, pasos)
    }

    private fun decodificarEscalada(contexto: Context, uri: Uri, ancho: Int, alto: Int): Bitmap? {
        if (ancho <= 0 || alto <= 0) return null
        var escala = 1
        while (max(ancho, alto) / escala > LADO_MINIATURA) escala *= 2
        val opciones = BitmapFactory.Options().apply { inSampleSize = escala }
        return try {
            contexto.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opciones)
            }
        } catch (e: Exception) {
            null
        }
    }

    private data class Par(val primero: String, val segundo: String)

    /** Promedia los píxeles para describir el tono general de la imagen. */
    private fun colorPredominante(bitmap: Bitmap): Par {
        var rojo = 0L
        var verde = 0L
        var azul = 0L
        var muestras = 0L
        val salto = max(1, min(bitmap.width, bitmap.height) / 64)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                rojo += (pixel shr 16) and 0xFF
                verde += (pixel shr 8) and 0xFF
                azul += pixel and 0xFF
                muestras++
                x += salto
            }
            y += salto
        }
        if (muestras == 0L) return Par("indefinido", "indefinida")
        val r = (rojo / muestras).toInt()
        val g = (verde / muestras).toInt()
        val b = (azul / muestras).toInt()
        val luminancia = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
        val brillo = when {
            luminancia < 0.25 -> "oscura"
            luminancia < 0.5 -> "media-oscura"
            luminancia < 0.75 -> "media-clara"
            else -> "clara"
        }
        val nombre = when {
            r > g + 20 && r > b + 20 -> "un rojo cálido"
            g > r + 20 && g > b + 20 -> "un verde"
            b > r + 20 && b > g + 20 -> "un azul frío"
            r > 200 && g > 200 && b > 200 -> "el blanco"
            r < 60 && g < 60 && b < 60 -> "el negro"
            r > g && g > b -> "un tono cálido (tierra)"
            else -> "un gris neutro"
        }
        return Par(
            "$nombre (#${"%02X%02X%02X".format(r, g, b)})",
            "$brillo (${(luminancia * 100).roundToInt()}%)",
        )
    }

    private fun leerExif(contexto: Context, uri: Uri): List<Pair<String, String>> {
        val datos = mutableListOf<Pair<String, String>>()
        try {
            contexto.contentResolver.openInputStream(uri)?.use { flujo ->
                val exif = ExifInterface(flujo)
                val marca = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()
                val modelo = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()
                if (!marca.isNullOrEmpty() || !modelo.isNullOrEmpty()) {
                    datos.add("Cámara" to listOfNotNull(marca, modelo).joinToString(" "))
                }
                exif.getAttribute(ExifInterface.TAG_DATETIME)?.let { datos.add("Tomada" to it) }
                exif.getAttribute(ExifInterface.TAG_F_NUMBER)?.let { datos.add("Apertura" to "f/$it") }
                exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.let { datos.add("Exposición" to "$it s") }
                exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)?.let { datos.add("ISO" to it) }
            }
        } catch (e: Exception) {
            // Sin EXIF no pasa nada: es información opcional.
        }
        return datos
    }

    // --------------------------------------------------------------- pdf

    private fun analizarPdf(
        contexto: Context, uri: Uri, nombre: String, mime: String, tamanio: Long,
    ): Adjunto {
        val pasos = mutableListOf<String>()
        var descriptor: ParcelFileDescriptor? = null
        var renderizador: PdfRenderer? = null
        try {
            descriptor = contexto.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("no pude abrir el archivo")
            renderizador = PdfRenderer(descriptor)
            val paginas = renderizador.pageCount
            pasos.add("Abrí el PDF con PdfRenderer: $paginas páginas")

            val primera = renderizador.openPage(0)
            val anchoPt = primera.width
            val altoPt = primera.height
            val escala = LADO_MINIATURA.toFloat() / max(anchoPt, altoPt)
            val miniatura = Bitmap.createBitmap(
                max(1, (anchoPt * escala).toInt()),
                max(1, (altoPt * escala).toInt()),
                Bitmap.Config.ARGB_8888,
            )
            miniatura.eraseColor(android.graphics.Color.WHITE)
            primera.render(miniatura, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            primera.close()
            pasos.add("Rendericé la portada a ${miniatura.width}x${miniatura.height} px")

            val tamanioHoja = describirHoja(anchoPt, altoPt)
            pasos.add("Medí la página: $anchoPt x $altoPt pt -> $tamanioHoja")

            val datos = listOf(
                "Páginas" to paginas.toString(),
                "Página 1" to "$anchoPt x $altoPt pt",
                "Formato" to tamanioHoja,
                "Orientación" to if (anchoPt > altoPt) "apaisada" else "vertical",
                "Tamaño" to pesoLegible(tamanio),
            )
            val resumen = "Es un PDF de $paginas ${if (paginas == 1) "página" else "páginas"}, " +
                "en $tamanioHoja ${if (anchoPt > altoPt) "apaisado" else "vertical"}, de ${pesoLegible(tamanio)}. " +
                "Te muestro la portada acá al lado.\n\n" +
                "No extraigo el texto: Android me deja dibujar las páginas, no leerlas."
            return Adjunto(nombre, "pdf", mime, tamanio, miniatura, datos, resumen, pasos)
        } catch (e: Exception) {
            return Adjunto(
                nombre, "pdf", mime, tamanio, null,
                listOf("Tamaño" to pesoLegible(tamanio)),
                "Recibí «$nombre», pero no pude abrirlo como PDF (${e.message ?: "archivo ilegible"}). " +
                    "Puede estar protegido con contraseña o dañado.",
                pasos + "Falló PdfRenderer: ${e.message}",
            )
        } finally {
            try { renderizador?.close() } catch (e: Exception) { }
            try { descriptor?.close() } catch (e: Exception) { }
        }
    }

    private fun describirHoja(anchoPt: Int, altoPt: Int): String {
        val menor = min(anchoPt, altoPt)
        val mayor = max(anchoPt, altoPt)
        return when {
            cerca(menor, 595) && cerca(mayor, 842) -> "A4"
            cerca(menor, 612) && cerca(mayor, 792) -> "Carta"
            cerca(menor, 842) && cerca(mayor, 1191) -> "A3"
            cerca(menor, 420) && cerca(mayor, 595) -> "A5"
            else -> "${menor}x${mayor} pt"
        }
    }

    private fun cerca(valor: Int, objetivo: Int, tolerancia: Int = 6) =
        kotlin.math.abs(valor - objetivo) <= tolerancia

    // ------------------------------------------------------------- video

    private fun analizarVideo(
        contexto: Context, uri: Uri, nombre: String, mime: String, tamanio: Long,
    ): Adjunto {
        val pasos = mutableListOf<String>()
        val lector = MediaMetadataRetriever()
        try {
            lector.setDataSource(contexto, uri)
            fun clave(k: Int): String? = lector.extractMetadata(k)

            val duracionMs = clave(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val ancho = clave(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val alto = clave(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotacion = clave(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val bitrate = clave(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
            val cuadros = clave(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull()
            pasos.add("Leí los metadatos con MediaMetadataRetriever")
            pasos.add("Duración ${duracionMs} ms, resolución ${ancho}x${alto}, rotación ${rotacion}°")

            // Un fotograma del 10% del video suele ser más representativo que el primero.
            val miniatura = lector.getFrameAtTime(duracionMs * 100, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            pasos.add(
                if (miniatura != null) "Extraje un fotograma del 10% de la duración"
                else "No pude extraer un fotograma (códec no soportado)"
            )

            val fps = if (cuadros != null && duracionMs > 0) cuadros * 1000.0 / duracionMs else null
            val visibleAncho = if (rotacion == 90 || rotacion == 270) alto else ancho
            val visibleAlto = if (rotacion == 90 || rotacion == 270) ancho else alto

            val datos = mutableListOf(
                "Duración" to duracionLegible(duracionMs),
                "Resolución" to "$visibleAncho x $visibleAlto px",
                "Calidad" to etiquetaResolucion(visibleAncho, visibleAlto),
                "Orientación" to if (visibleAncho >= visibleAlto) "horizontal" else "vertical",
                "Formato" to mime,
                "Tamaño" to pesoLegible(tamanio),
            )
            if (bitrate != null) datos.add("Bitrate" to "${bitrate / 1000} kbps")
            if (fps != null) datos.add("Cuadros por segundo" to String.format(Locale.US, "%.1f fps", fps))

            val resumen = "Es un video de ${duracionLegible(duracionMs)} en $visibleAncho x $visibleAlto " +
                "(${etiquetaResolucion(visibleAncho, visibleAlto)}), " +
                "${if (visibleAncho >= visibleAlto) "horizontal" else "vertical"}, de ${pesoLegible(tamanio)}." +
                (if (fps != null) " Va a ${String.format(Locale.US, "%.0f", fps)} cuadros por segundo." else "") +
                "\n\nTe saqué un fotograma de muestra. Los metadatos los leo enteros; el contenido, no."
            return Adjunto(nombre, "video", mime, tamanio, miniatura, datos, resumen, pasos)
        } catch (e: Exception) {
            return Adjunto(
                nombre, "video", mime, tamanio, null,
                listOf("Tamaño" to pesoLegible(tamanio)),
                "Recibí «$nombre» pero no pude leer sus metadatos (${e.message ?: "formato no soportado"}).",
                pasos + "Falló MediaMetadataRetriever: ${e.message}",
            )
        } finally {
            try { lector.release() } catch (e: Exception) { }
        }
    }

    private fun etiquetaResolucion(ancho: Int, alto: Int): String {
        val menor = min(ancho, alto)
        return when {
            menor >= 2160 -> "4K"
            menor >= 1440 -> "2K / QHD"
            menor >= 1080 -> "Full HD"
            menor >= 720 -> "HD"
            menor >= 480 -> "SD"
            else -> "baja"
        }
    }

    // ------------------------------------------------------------ comunes

    private fun nombreYTamanio(contexto: Context, uri: Uri): Pair<String, Long> {
        var nombre = uri.lastPathSegment?.substringAfterLast('/') ?: "archivo"
        var tamanio = 0L
        try {
            contexto.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val iNombre = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val iTamanio = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (iNombre >= 0 && !cursor.isNull(iNombre)) nombre = cursor.getString(iNombre)
                    if (iTamanio >= 0 && !cursor.isNull(iTamanio)) tamanio = cursor.getLong(iTamanio)
                }
            }
        } catch (e: Exception) {
            // Algunos proveedores no exponen estas columnas; seguimos con lo que haya.
        }
        return nombre to tamanio
    }

    fun pesoLegible(bytes: Long): String = when {
        bytes <= 0 -> "tamaño desconocido"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }

    private fun duracionLegible(ms: Long): String {
        if (ms <= 0) return "duración desconocida"
        val totalSegundos = ms / 1000
        val minutos = totalSegundos / 60
        val segundos = totalSegundos % 60
        return if (minutos > 0) "${minutos}m ${segundos}s" else "${segundos}s"
    }

    private fun proporcion(ancho: Int, alto: Int): String {
        if (ancho <= 0 || alto <= 0) return "desconocida"
        val divisor = mcd(ancho, alto)
        val a = ancho / divisor
        val b = alto / divisor
        return if (a <= 40 && b <= 40) "$a:$b" else String.format(Locale.US, "%.2f:1", ancho.toDouble() / alto)
    }

    private fun mcd(a: Int, b: Int): Int = if (b == 0) a else mcd(b, a % b)
}
