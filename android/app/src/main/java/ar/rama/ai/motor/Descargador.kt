package ar.rama.ai.motor

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** Cómo va una descarga en curso. */
data class Progreso(val bytesRecibidos: Long, val bytesTotales: Long) {
    val fraccion: Float get() = if (bytesTotales > 0) bytesRecibidos.toFloat() / bytesTotales else 0f
}

sealed class ResultadoDescarga {
    data class Listo(val archivo: File) : ResultadoDescarga()
    data class Fallo(val motivo: String) : ResultadoDescarga()
    object Cancelada : ResultadoDescarga()
}

/**
 * Descarga los pesos del modelo desde donde los publicó su autor.
 *
 * Es lo único que Rama baja de internet, una sola vez. La descarga se puede
 * reanudar: en un archivo de cientos de megas, cortar y volver a empezar no
 * es una opción razonable con datos móviles.
 */
class Descargador(private val destino: File) {

    @Volatile
    private var cancelado = false

    fun cancelar() {
        cancelado = true
    }

    fun descargar(
        modelo: ModeloDisponible,
        alProgresar: (Progreso) -> Unit,
    ): ResultadoDescarga {
        val urlResuelta = try {
            resolverUrl(modelo)
        } catch (e: Exception) {
            return ResultadoDescarga.Fallo("No pude averiguar dónde está el modelo: ${e.message}")
        } ?: return ResultadoDescarga.Fallo(
            "No encontré el modelo en ninguno de los ${modelo.origenes.size} repositorios " +
                "que conozco:\n\n${intentos.joinToString("\n")}\n\n" +
                "Bajá el .gguf desde el navegador y elegilo con «Importar un .gguf»."
        )

        val parcial = File(destino.parentFile, destino.name + ".parcial")
        var yaDescargado = if (parcial.exists()) parcial.length() else 0L

        return try {
            val conexion = abrir(urlResuelta, desdeByte = yaDescargado)
            val codigo = conexion.responseCode

            if (yaDescargado > 0 && codigo != HttpURLConnection.HTTP_PARTIAL) {
                // El servidor ignoró el Range: arrancamos de cero.
                parcial.delete()
                yaDescargado = 0
            }
            if (codigo != HttpURLConnection.HTTP_OK && codigo != HttpURLConnection.HTTP_PARTIAL) {
                return ResultadoDescarga.Fallo("El servidor respondió $codigo")
            }

            val restantes = conexion.contentLengthLong.coerceAtLeast(0)
            val totales = if (restantes > 0) yaDescargado + restantes else modelo.bytesAproximados

            conexion.inputStream.use { entrada ->
                java.io.FileOutputStream(parcial, yaDescargado > 0).use { salida ->
                    val buffer = ByteArray(1 shl 16)
                    var acumulado = yaDescargado
                    var ultimoAviso = 0L
                    while (true) {
                        if (cancelado) return ResultadoDescarga.Cancelada
                        val leidos = entrada.read(buffer)
                        if (leidos <= 0) break
                        salida.write(buffer, 0, leidos)
                        acumulado += leidos
                        // Avisamos cada medio mega: alcanza para una barra fluida
                        // sin inundar el hilo principal.
                        if (acumulado - ultimoAviso > 512 * 1024) {
                            ultimoAviso = acumulado
                            alProgresar(Progreso(acumulado, totales))
                        }
                    }
                    alProgresar(Progreso(acumulado, totales))
                }
            }

            if (!esGguf(parcial)) {
                parcial.delete()
                return ResultadoDescarga.Fallo(
                    "Lo que bajó no es un modelo GGUF válido. Puede que el enlace haya cambiado."
                )
            }
            destino.delete()
            if (!parcial.renameTo(destino)) {
                return ResultadoDescarga.Fallo("No pude guardar el modelo en su lugar definitivo")
            }
            ResultadoDescarga.Listo(destino)
        } catch (e: Exception) {
            ResultadoDescarga.Fallo(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Lo que se intentó y por qué falló, para poder contarlo en pantalla. */
    var intentos: List<String> = emptyList()
        private set

    private fun resolverUrl(modelo: ModeloDisponible): String? {
        val (url, pasos) = resolver(modelo)
        intentos = pasos
        return url
    }

    /** Le pregunta al repositorio qué archivos tiene y elige el más apropiado. */
    internal fun buscarEnRepositorio(repositorio: String): String? {
        val json = try {
            val conexion = URL("https://huggingface.co/api/models/$repositorio")
                .openConnection() as HttpURLConnection
            conexion.connectTimeout = 20_000
            conexion.readTimeout = 20_000
            conexion.setRequestProperty("User-Agent", "RamaAI")
            if (conexion.responseCode !in 200..299) return null
            conexion.inputStream.bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            return null
        } catch (e: Exception) {
            return null
        }

        val archivos = try {
            JSONObject(json).optJSONArray("siblings") ?: return null
        } catch (e: Exception) {
            return null
        }
        val nombres = (0 until archivos.length())
            .mapNotNull { archivos.optJSONObject(it)?.optString("rfilename") }
            .filter { it.endsWith(".gguf", ignoreCase = true) }
            // Los modelos partidos en varios archivos no los sabemos juntar.
            .filterNot { it.contains("-of-") }

        val elegido = nombres.firstOrNull { it.contains("Q4_K_M", ignoreCase = true) }
            ?: nombres.firstOrNull { it.contains("Q4", ignoreCase = true) }
            ?: nombres.firstOrNull { it.contains("Q5", ignoreCase = true) }
            ?: nombres.firstOrNull()
            ?: return null
        return urlDeArchivo(repositorio, elegido)
    }

    internal fun existe(url: String): Boolean = try {
        val conexion = abrir(url, desdeByte = 0, soloCabecera = true)
        conexion.responseCode in 200..299
    } catch (e: Exception) {
        false
    }

    private fun abrir(url: String, desdeByte: Long, soloCabecera: Boolean = false): HttpURLConnection {
        val conexion = URL(url).openConnection() as HttpURLConnection
        conexion.requestMethod = if (soloCabecera) "HEAD" else "GET"
        conexion.connectTimeout = 20_000
        conexion.readTimeout = 60_000
        conexion.instanceFollowRedirects = true
        conexion.setRequestProperty("User-Agent", "RamaAI")
        if (desdeByte > 0) conexion.setRequestProperty("Range", "bytes=$desdeByte-")
        return conexion
    }

    companion object {
        fun urlDeArchivo(repositorio: String, archivo: String) =
            "https://huggingface.co/$repositorio/resolve/main/$archivo?download=true"

        /**
         * Busca el archivo en cada origen conocido y, si ninguno responde, le
         * pregunta a cada repositorio qué archivos tiene.
         *
         * Con un solo origen fijo, cualquier renombrado deja la app sin poder
         * descargar. Acá hay que quedarse sin todos los caminos para fallar.
         * Devuelve la URL y el registro de lo intentado.
         */
        fun resolver(modelo: ModeloDisponible): Pair<String?, List<String>> {
            val intentos = mutableListOf<String>()
            val sonda = Descargador(File("/dev/null"))

            for (origen in modelo.origenes) {
                val directa = urlDeArchivo(origen.repositorio, origen.archivo)
                if (sonda.existe(directa)) return directa to intentos
                intentos.add("${origen.repositorio}/${origen.archivo}: no está")
            }
            for (origen in modelo.origenes) {
                val encontrado = sonda.buscarEnRepositorio(origen.repositorio)
                if (encontrado != null) return encontrado to intentos
                intentos.add("${origen.repositorio}: sin .gguf utilizable")
            }
            return null to intentos
        }

        /** Los GGUF empiezan con estas cuatro letras. Barato de verificar. */
        fun esGguf(archivo: File): Boolean = try {
            archivo.inputStream().use { flujo ->
                val magia = ByteArray(4)
                flujo.read(magia) == 4 && String(magia) == "GGUF"
            }
        } catch (e: Exception) {
            false
        }
    }
}
