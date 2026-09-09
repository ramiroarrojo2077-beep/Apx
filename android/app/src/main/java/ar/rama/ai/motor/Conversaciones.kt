package ar.rama.ai.motor

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Lo que se muestra en la lista de chats, sin cargar la conversación entera. */
data class ResumenConversacion(
    val id: String,
    val titulo: String,
    val actualizada: Long,
    val cantidadMensajes: Int,
)

/**
 * Los chats guardados, uno por archivo.
 *
 * Viven en el almacenamiento privado de la app: no salen del teléfono ni los
 * ve nadie más. Borrar la app se los lleva a todos, que es exactamente lo que
 * uno espera.
 */
class Conversaciones(private val carpeta: File) {

    fun listar(): List<ResumenConversacion> {
        val archivos = carpeta.listFiles { archivo -> archivo.name.endsWith(EXTENSION) }
            ?: return emptyList()
        return archivos
            .mapNotNull { leerResumen(it) }
            .sortedByDescending { it.actualizada }
    }

    /** Guarda (o pisa) una conversación. Devuelve null si no había nada que guardar. */
    fun guardar(id: String, mensajes: List<Mensaje>, titulo: String? = null): ResumenConversacion? {
        val utiles = mensajes.filter { it.rol != "system" }
        if (utiles.isEmpty()) return null

        carpeta.mkdirs()
        val archivo = archivoDe(id)
        val creada = leerResumen(archivo)?.let { leerCampoLargo(archivo, "creada") } ?: ahora()
        val ahora = ahora()

        val arreglo = JSONArray()
        for (mensaje in utiles) {
            arreglo.put(JSONObject().put("rol", mensaje.rol).put("contenido", mensaje.contenido))
        }
        val nombre = titulo ?: tituloDe(utiles)
        val raiz = JSONObject()
            .put("id", id)
            .put("titulo", nombre)
            .put("creada", creada)
            .put("actualizada", ahora)
            .put("mensajes", arreglo)

        escribirAtomico(archivo, raiz.toString())
        return ResumenConversacion(id, nombre, ahora, utiles.size)
    }

    fun cargar(id: String): List<Mensaje> {
        val texto = try {
            archivoDe(id).takeIf { it.exists() }?.readText() ?: return emptyList()
        } catch (e: Exception) {
            return emptyList()
        }
        return try {
            val arreglo = JSONObject(texto).optJSONArray("mensajes") ?: return emptyList()
            (0 until arreglo.length()).mapNotNull { i ->
                arreglo.optJSONObject(i)?.let { Mensaje(it.optString("rol"), it.optString("contenido")) }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun borrar(id: String): Boolean = archivoDe(id).delete()

    fun borrarTodo(): Int {
        val archivos = carpeta.listFiles { archivo -> archivo.name.endsWith(EXTENSION) } ?: return 0
        return archivos.count { it.delete() }
    }

    fun renombrar(id: String, titulo: String): Boolean {
        val mensajes = cargar(id)
        if (mensajes.isEmpty()) return false
        guardar(id, mensajes, titulo.trim().take(LARGO_TITULO))
        return true
    }

    private fun archivoDe(id: String) = File(carpeta, "$id$EXTENSION")

    private fun leerResumen(archivo: File): ResumenConversacion? = try {
        val raiz = JSONObject(archivo.readText())
        ResumenConversacion(
            id = raiz.optString("id", archivo.nameWithoutExtension),
            titulo = raiz.optString("titulo", SIN_TITULO),
            actualizada = raiz.optLong("actualizada", archivo.lastModified()),
            cantidadMensajes = raiz.optJSONArray("mensajes")?.length() ?: 0,
        )
    } catch (e: Exception) {
        null  // un archivo corrupto no debe tumbar la lista entera
    }

    private fun leerCampoLargo(archivo: File, campo: String): Long? = try {
        JSONObject(archivo.readText()).optLong(campo).takeIf { it > 0 }
    } catch (e: Exception) {
        null
    }

    private fun escribirAtomico(archivo: File, contenido: String) {
        val temporal = File(archivo.parentFile, archivo.name + ".tmp")
        temporal.writeText(contenido)
        if (!temporal.renameTo(archivo)) {
            archivo.writeText(contenido)
            temporal.delete()
        }
    }

    companion object {
        private const val EXTENSION = ".chat.json"
        private const val LARGO_TITULO = 48
        const val SIN_TITULO = "Chat sin título"

        /** Puede sobreescribirse en los tests para tener fechas previsibles. */
        var ahora: () -> Long = { System.currentTimeMillis() }

        private val contador = java.util.concurrent.atomic.AtomicLong(0)

        /**
         * Un identificador que no se repite.
         *
         * Sólo con la hora y un número al azar chico, dos chats creados en el
         * mismo milisegundo podían caer en el mismo archivo y pisarse. El
         * contador lo hace imposible dentro de la sesión, y el azar cubre el
         * caso de dos sesiones arrancando a la vez.
         */
        fun nuevoId(): String =
            "chat-${System.currentTimeMillis()}-${contador.incrementAndGet()}-" +
                "%06x".format((0..0xFFFFFF).random())

        /**
         * El título sale de lo primero que preguntó el usuario: es lo que uno
         * recuerda de una conversación, mucho más que la respuesta.
         */
        fun tituloDe(mensajes: List<Mensaje>): String {
            val primera = mensajes.firstOrNull { it.rol == "user" }?.contenido?.trim()
            if (primera.isNullOrEmpty()) return SIN_TITULO
            val enUnaLinea = primera.replace(Regex("\\s+"), " ")
            return if (enUnaLinea.length <= LARGO_TITULO) enUnaLinea
            else enUnaLinea.take(LARGO_TITULO - 1).trimEnd() + "…"
        }
    }
}
