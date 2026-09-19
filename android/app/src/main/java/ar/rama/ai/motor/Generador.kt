package ar.rama.ai.motor

import java.io.File

/** Un turno de la conversación, tal como se lo pasamos al modelo. */
data class Mensaje(val rol: String, val contenido: String)

/**
 * El modelo de lenguaje corriendo dentro del teléfono.
 *
 * Rama no llama a ninguna IA ajena: los pesos están en el disco del usuario y
 * la generación ocurre en su procesador. Sin cuenta, sin API key, sin que el
 * texto salga del aparato.
 */
class Generador private constructor(
    private val handle: Long,
    val archivo: File,
) {

    @Volatile
    private var cerrado = false

    val info: String by lazy { if (cerrado) "" else Llama.nativeInfo(handle) }

    /**
     * Genera la respuesta entregando cada fragmento a [alFragmento].
     * Devolver false desde el receptor corta la generación.
     */
    fun responder(
        conversacion: List<Mensaje>,
        maxTokens: Int = 512,
        temperatura: Float = 0.7f,
        topP: Float = 0.95f,
        topK: Int = 40,
        semilla: Long = System.nanoTime() and 0xFFFFFFFFL,
        alFragmento: (String) -> Boolean,
    ): Int {
        if (cerrado) return -1
        val prompt = armarPrompt(conversacion)
        return Llama.nativeGenerar(
            handle, prompt, maxTokens, temperatura, topP, topK, semilla,
            Llama.Receptor { texto -> alFragmento(texto) },
        )
    }

    /**
     * Usa la plantilla de chat que trae el GGUF; si no trae, cae en ChatML,
     * que es el formato de la mayoría de los modelos chicos actuales.
     */
    fun armarPrompt(conversacion: List<Mensaje>): String {
        if (!cerrado) {
            val propia = Llama.nativeFormatearChat(
                handle,
                conversacion.map { it.rol }.toTypedArray(),
                conversacion.map { it.contenido }.toTypedArray(),
                true,
            )
            if (propia.isNotEmpty()) return propia
        }
        return conversacion.joinToString("") {
            "<|im_start|>${it.rol}\n${it.contenido}<|im_end|>\n"
        } + "<|im_start|>assistant\n"
    }

    fun cancelar() {
        if (!cerrado) Llama.nativeCancelar(handle)
    }

    fun cerrar() {
        if (cerrado) return
        cerrado = true
        Llama.nativeCerrar(handle)
    }

    companion object {
        /** Contexto acotado: en un teléfono, la memoria es el límite real. */
        const val CONTEXTO = 4096

        /**
         * Abre un modelo GGUF. Devuelve null si no se pudo cargar (archivo
         * corrupto, formato desconocido o memoria insuficiente).
         */
        fun abrir(
            archivo: File,
            contexto: Int = contextoRecomendado(archivo),
            hilos: Int = hilosRecomendados(),
        ): Generador? {
            if (!Llama.disponible || !archivo.exists()) return null
            val handle = Llama.nativeAbrir(archivo.absolutePath, contexto, hilos)
            return if (handle == 0L) null else Generador(handle, archivo)
        }

        /**
         * La ventana de contexto con la que se abre un modelo.
         *
         * Antes los modelos de más de 800 MB se abrían con la mitad, porque la
         * caché de atención en 16 bits se comía cientos de megas. Ya no: la
         * caché pasó a 8 bits y el catálogo tiene techo, así que hasta el más
         * pesado se lleva unos 300 MB de caché a contexto completo. Eso entra
         * de sobra en el margen que la lista ya le reserva a cada modelo.
         *
         * Con el contexto entero, un pedido de código puede traer el archivo
         * pegado, el historial y todavía dejarle al modelo los mil tokens que
         * necesita para escribir una función sin cortarla al medio.
         *
         * Sólo se achica para un archivo importado a mano, que puede ser
         * cualquier cosa y bastante más grande que lo que ofrece la lista.
         */
        fun contextoRecomendado(archivo: File): Int =
            if (archivo.length() > 4L * 1024 * 1024 * 1024) 2048 else CONTEXTO

        /**
         * Sólo los núcleos rápidos del teléfono: sumar los lentos hace que los
         * rápidos los esperen. La cuenta está en [Nucleos].
         */
        fun hilosRecomendados(): Int = Nucleos.recomendados()
    }
}
