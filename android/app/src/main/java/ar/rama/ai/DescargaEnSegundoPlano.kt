package ar.rama.ai

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import ar.rama.ai.motor.ModeloDisponible
import java.io.File

/** En qué anda una descarga del sistema. */
sealed class EstadoDescarga {
    object Ninguna : EstadoDescarga()
    data class EnCurso(val bytes: Long, val totales: Long, val enPausa: Boolean) : EstadoDescarga() {
        val fraccion: Float get() = if (totales > 0) bytes.toFloat() / totales else 0f
    }
    data class Terminada(val archivo: File) : EstadoDescarga()
    data class Fallo(val motivo: String) : EstadoDescarga()
}

/**
 * Descarga el modelo con el gestor del sistema.
 *
 * Es la diferencia entre poder salir de la app o no: el gestor de Android
 * sigue bajando con la pantalla apagada, sobrevive a que Android cierre la
 * app, reanuda si se corta la conexión y muestra el progreso en la barra de
 * notificaciones. Un giga por una conexión móvil no se baja de otra forma.
 */
class DescargaEnSegundoPlano(private val contexto: Context) {

    private val gestor: DownloadManager? =
        contexto.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager

    private val preferencias = contexto.getSharedPreferences("descargas", Context.MODE_PRIVATE)

    /** Dónde queda el archivo. El modelo se usa desde acá, sin copiarlo. */
    fun archivoDe(modelo: ModeloDisponible): File =
        File(contexto.getExternalFilesDir(CARPETA), "${modelo.id}.gguf")

    fun idDe(modelo: ModeloDisponible): Long = preferencias.getLong(modelo.id, -1L)

    /** Encola la descarga y devuelve su identificador, o -1 si no se pudo. */
    fun encolar(modelo: ModeloDisponible, url: String): Long {
        val administrador = gestor ?: return -1L
        cancelar(modelo)
        archivoDe(modelo).delete()

        return try {
            val peticion = DownloadManager.Request(Uri.parse(url))
                .setTitle("Rama AI · ${modelo.nombre}")
                .setDescription("Descargando el modelo de lenguaje")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(contexto, CARPETA, "${modelo.id}.gguf")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .addRequestHeader("User-Agent", "RamaAI")
            val id = administrador.enqueue(peticion)
            preferencias.edit().putLong(modelo.id, id).apply()
            id
        } catch (e: Exception) {
            -1L
        }
    }

    fun cancelar(modelo: ModeloDisponible) {
        val id = idDe(modelo)
        if (id >= 0) {
            try {
                gestor?.remove(id)
            } catch (e: Exception) {
                // Ya no existía: nada que cancelar.
            }
            preferencias.edit().remove(modelo.id).apply()
        }
    }

    fun estado(modelo: ModeloDisponible): EstadoDescarga {
        val archivo = archivoDe(modelo)
        val id = idDe(modelo)
        if (id < 0) {
            return if (archivo.exists()) EstadoDescarga.Terminada(archivo) else EstadoDescarga.Ninguna
        }

        val administrador = gestor ?: return EstadoDescarga.Ninguna
        val cursor = try {
            administrador.query(DownloadManager.Query().setFilterById(id))
        } catch (e: Exception) {
            null
        } ?: return EstadoDescarga.Ninguna

        cursor.use {
            if (!it.moveToFirst()) {
                preferencias.edit().remove(modelo.id).apply()
                return if (archivo.exists()) EstadoDescarga.Terminada(archivo) else EstadoDescarga.Ninguna
            }
            val estado = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val bajados = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val totales = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val razon = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))

            return when (estado) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    preferencias.edit().remove(modelo.id).apply()
                    EstadoDescarga.Terminada(archivo)
                }
                DownloadManager.STATUS_FAILED -> {
                    preferencias.edit().remove(modelo.id).apply()
                    EstadoDescarga.Fallo(explicar(razon))
                }
                DownloadManager.STATUS_PAUSED ->
                    EstadoDescarga.EnCurso(bajados, totales, enPausa = true)
                else ->
                    EstadoDescarga.EnCurso(bajados, totales, enPausa = false)
            }
        }
    }

    companion object {
        private const val CARPETA = "modelos"

        /** Traduce los códigos del gestor a algo que se pueda leer. */
        fun explicar(razon: Int): String = when (razon) {
            DownloadManager.ERROR_INSUFFICIENT_SPACE -> "no hay espacio suficiente en el teléfono"
            DownloadManager.ERROR_DEVICE_NOT_FOUND -> "no encuentro dónde guardarlo"
            DownloadManager.ERROR_CANNOT_RESUME -> "se cortó y no se pudo reanudar"
            DownloadManager.ERROR_HTTP_DATA_ERROR -> "se cortó la transferencia"
            DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "el enlace da demasiadas vueltas"
            DownloadManager.ERROR_FILE_ERROR -> "hubo un problema con el archivo"
            DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "el servidor respondió algo inesperado"
            in 400..599 -> "el servidor respondió $razon"
            else -> "error $razon"
        }
    }
}
