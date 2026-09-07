package ar.rama.ai.motor

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Dónde persiste Rama lo que aprende. Se abstrae para poder testear sin disco. */
interface Almacen {
    fun leer(): String?
    fun escribir(contenido: String)
}

/** Almacén volátil, para tests. */
class AlmacenEnMemoria(private var contenido: String? = null) : Almacen {
    override fun leer(): String? = contenido
    override fun escribir(contenido: String) { this.contenido = contenido }
}

/** Almacén en disco con escritura atómica: nunca queda un archivo a medias. */
class AlmacenArchivo(private val archivo: File) : Almacen {
    override fun leer(): String? = try {
        if (archivo.exists()) archivo.readText() else null
    } catch (e: Exception) {
        null
    }

    override fun escribir(contenido: String) {
        archivo.parentFile?.mkdirs()
        val temporal = File(archivo.parentFile, archivo.name + ".tmp")
        temporal.writeText(contenido)
        if (!temporal.renameTo(archivo)) {
            archivo.writeText(contenido)
            temporal.delete()
        }
    }
}

/** Un par pregunta/respuesta que le enseñó el usuario. */
data class Hecho(val pregunta: String, val respuesta: String)

/**
 * Guarda hechos aprendidos y el estado de la conversación en curso.
 * Lo aprendido se persiste; el historial vive sólo en la sesión.
 */
class Memoria(private val almacen: Almacen = AlmacenEnMemoria()) {

    val aprendido = mutableListOf<Hecho>()
    private val perfil = HashMap<String, String>()
    val historial = ArrayDeque<Pair<String, String>>()
    var ultimaPreguntaSinRespuesta: String? = null

    init {
        cargar()
    }

    private fun cargar() {
        val crudo = almacen.leer() ?: return
        try {
            val raiz = JSONObject(crudo)
            val hechos = raiz.optJSONArray("aprendido") ?: JSONArray()
            for (i in 0 until hechos.length()) {
                val h = hechos.getJSONObject(i)
                aprendido.add(Hecho(h.getString("pregunta"), h.getString("respuesta")))
            }
            val guardado = raiz.optJSONObject("perfil")
            if (guardado != null) {
                for (clave in guardado.keys()) perfil[clave] = guardado.getString(clave)
            }
        } catch (e: Exception) {
            // Una memoria corrupta no debe impedir que Rama arranque.
            aprendido.clear()
            perfil.clear()
        }
    }

    private fun guardar() {
        val hechos = JSONArray()
        for (h in aprendido) {
            hechos.put(JSONObject().put("pregunta", h.pregunta).put("respuesta", h.respuesta))
        }
        val raiz = JSONObject()
            .put("aprendido", hechos)
            .put("perfil", JSONObject(perfil.toMap()))
        almacen.escribir(raiz.toString(2))
    }

    /** Registra un par pregunta/respuesta, actualizando si ya existía. */
    fun aprender(pregunta: String, respuesta: String) {
        val p = pregunta.trim()
        val r = respuesta.trim()
        val indice = aprendido.indexOfFirst { it.pregunta.equals(p, ignoreCase = true) }
        if (indice >= 0) aprendido[indice] = Hecho(aprendido[indice].pregunta, r)
        else aprendido.add(Hecho(p, r))
        guardar()
    }

    fun olvidar(pregunta: String): Boolean {
        val objetivo = pregunta.trim()
        val quedaron = aprendido.filterNot { it.pregunta.equals(objetivo, ignoreCase = true) }
        if (quedaron.size == aprendido.size) return false
        aprendido.clear()
        aprendido.addAll(quedaron)
        guardar()
        return true
    }

    fun recordar(clave: String, valor: String) {
        perfil[clave] = valor
        guardar()
    }

    fun recuerdo(clave: String): String? = perfil[clave]

    fun registrarTurno(quien: String, texto: String) {
        historial.addLast(quien to texto)
        while (historial.size > MAX_HISTORIAL) historial.removeFirst()
    }

    companion object {
        const val MAX_HISTORIAL = 40
    }
}
