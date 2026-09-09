package ar.rama.ai.motor

/**
 * Enlace con llama.cpp, compilado dentro de la app.
 *
 * Es la capa más delgada posible: todo lo que decide algo vive en [Generador].
 * Acá sólo cruzamos la frontera con C++.
 */
object Llama {

    /** Recibe cada fragmento generado. Devolver false corta la generación. */
    fun interface Receptor {
        fun onToken(texto: String): Boolean
    }

    @Volatile
    var disponible: Boolean = false
        private set

    /** Motivo por el que no se pudo cargar la librería nativa, si falló. */
    var motivoNoDisponible: String? = null
        private set

    init {
        try {
            System.loadLibrary("rama_llama")
            val faltan = nativeFaltantes()
            if (faltan.isNotEmpty()) {
                motivoNoDisponible =
                    "El procesador de este teléfono no tiene $faltan. Rama compila sus " +
                    "modelos con esas instrucciones porque son las que los hacen rápidos, " +
                    "y sin ellas no puede generar."
            } else {
                nativeIniciar()
                disponible = true
            }
        } catch (e: Throwable) {
            motivoNoDisponible = "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private external fun nativeIniciar()

    /**
     * Las instrucciones que le faltan a este procesador, separadas por coma, o
     * vacío si están todas. Se consulta antes de cargar cualquier modelo.
     */
    private external fun nativeFaltantes(): String

    /** Devuelve 0 si el modelo no se pudo abrir. */
    external fun nativeAbrir(ruta: String, nCtx: Int, nHilos: Int): Long

    external fun nativeCerrar(handle: Long)

    external fun nativeInfo(handle: Long): String

    /** Aplica la plantilla de chat del propio GGUF. Vacío si no trae ninguna. */
    external fun nativeFormatearChat(
        handle: Long,
        roles: Array<String>,
        contenidos: Array<String>,
        agregarAsistente: Boolean,
    ): String

    external fun nativeGenerar(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperatura: Float,
        topP: Float,
        topK: Int,
        semilla: Long,
        receptor: Receptor,
    ): Int

    external fun nativeCancelar(handle: Long)
}
