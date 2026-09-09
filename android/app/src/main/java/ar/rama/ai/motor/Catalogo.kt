package ar.rama.ai.motor

/** Dónde vive un archivo de modelo: repositorio y nombre dentro de él. */
data class Origen(val repositorio: String, val archivo: String)

/** Un modelo que Rama puede descargar y usar. */
data class ModeloDisponible(
    val id: String,
    val nombre: String,
    val familia: String,
    val bytesAproximados: Long,
    val ramRecomendada: String,
    /** De 1 a 4: cuán seguido acierta en datos y sigue instrucciones. */
    val precision: Int,
    /** De 1 a 4: qué tan rápido responde en un teléfono común. */
    val velocidad: Int,
    val descripcion: String,
    /**
     * Varios lugares donde buscarlo, en orden.
     *
     * Los repositorios se renombran y reorganizan seguido; con un solo origen,
     * cualquier cambio deja la app sin poder bajar nada.
     */
    val origenes: List<Origen>,
) {
    val repositorio: String get() = origenes.first().repositorio
    val archivo: String get() = origenes.first().archivo

    companion object {
        /** Cuántos tramos tiene el medidor de precisión y velocidad. */
        const val ESCALA = 4
    }
}

/**
 * Los modelos que ofrece la app, del más liviano al más capaz.
 *
 * Son GGUF publicados por sus autores; Rama los descarga y los corre, no los
 * consulta por internet. La elección es siempre el mismo canje: más tamaño es
 * más precisión y menos velocidad.
 */
object Catalogo {

    private fun qwen(tamanio: String, archivo: String) = listOf(
        Origen("Qwen/Qwen3-$tamanio-GGUF", archivo),
        Origen("unsloth/Qwen3-$tamanio-GGUF", archivo),
        Origen("ggml-org/Qwen3-$tamanio-GGUF", archivo),
        Origen("bartowski/Qwen_Qwen3-$tamanio-GGUF", "Qwen_Qwen3-$tamanio-${archivo.substringAfterLast('-')}"),
    )

    val QWEN_06_R = ModeloDisponible(
        id = "qwen3-0.6b",
        nombre = "Qwen3 0.6B",
        familia = "Qwen",
        bytesAproximados = 400L * 1024 * 1024,
        ramRecomendada = "3 GB",
        precision = 1,
        velocidad = 4,
        descripcion = "El más liviano y rápido: anda en cualquier teléfono y contesta en " +
            "pocos segundos. Escribe bien, pero se equivoca seguido en datos.",
        origenes = qwen("0.6B", "Qwen3-0.6B-Q4_K_M.gguf"),
    )

    val QWEN_06_PRECISO = ModeloDisponible(
        id = "qwen3-0.6b-q8",
        nombre = "Qwen3 0.6B · alta fidelidad",
        familia = "Qwen",
        bytesAproximados = 700L * 1024 * 1024,
        ramRecomendada = "4 GB",
        precision = 2,
        velocidad = 4,
        descripcion = "El mismo modelo chico pero comprimido con mucha menos pérdida (Q8 " +
            "en vez de Q4). En modelos tan chicos la compresión duele más que en los " +
            "grandes: esto lo mejora bastante y sigue siendo rápido.",
        origenes = qwen("0.6B", "Qwen3-0.6B-Q8_0.gguf"),
    )

    val GEMMA_1B = ModeloDisponible(
        id = "gemma3-1b",
        nombre = "Gemma 3 1B",
        familia = "Gemma",
        bytesAproximados = 800L * 1024 * 1024,
        ramRecomendada = "4 GB",
        precision = 2,
        velocidad = 4,
        descripcion = "De la familia de Google. Buen español y respuestas ordenadas, " +
            "con un tamaño todavía cómodo.",
        origenes = listOf(
            Origen("ggml-org/gemma-3-1b-it-GGUF", "gemma-3-1b-it-Q4_K_M.gguf"),
            Origen("unsloth/gemma-3-1b-it-GGUF", "gemma-3-1b-it-Q4_K_M.gguf"),
            Origen("bartowski/google_gemma-3-1b-it-GGUF", "google_gemma-3-1b-it-Q4_K_M.gguf"),
        ),
    )

    val LLAMA_1B = ModeloDisponible(
        id = "llama32-1b",
        nombre = "Llama 3.2 1B",
        familia = "Llama",
        bytesAproximados = 810L * 1024 * 1024,
        ramRecomendada = "4 GB",
        precision = 2,
        velocidad = 4,
        descripcion = "De la familia de Meta. Escribe con naturalidad en español y es " +
            "una buena alternativa si los Qwen te fallan.",
        origenes = listOf(
            Origen("bartowski/Llama-3.2-1B-Instruct-GGUF", "Llama-3.2-1B-Instruct-Q4_K_M.gguf"),
            Origen("unsloth/Llama-3.2-1B-Instruct-GGUF", "Llama-3.2-1B-Instruct-Q4_K_M.gguf"),
            Origen("hugging-quants/Llama-3.2-1B-Instruct-Q4_K_M-GGUF", "llama-3.2-1b-instruct-q4_k_m.gguf"),
        ),
    )

    val QWEN_17 = ModeloDisponible(
        id = "qwen3-1.7b",
        nombre = "Qwen3 1.7B",
        familia = "Qwen",
        bytesAproximados = 1150L * 1024 * 1024,
        ramRecomendada = "6 GB",
        precision = 2,
        velocidad = 3,
        descripcion = "Bastante más coherente y con más conocimiento propio que los de " +
            "menos de un giga. Es el punto de equilibrio para un teléfono actual.",
        origenes = qwen("1.7B", "Qwen3-1.7B-Q4_K_M.gguf"),
    )

    val LLAMA_3B = ModeloDisponible(
        id = "llama32-3b",
        nombre = "Llama 3.2 3B",
        familia = "Llama",
        bytesAproximados = 2020L * 1024 * 1024,
        ramRecomendada = "8 GB",
        precision = 3,
        velocidad = 2,
        descripcion = "Un salto real de calidad: razona mejor y se equivoca mucho menos. " +
            "Pide un teléfono holgado y tarda bastante por respuesta.",
        origenes = listOf(
            Origen("bartowski/Llama-3.2-3B-Instruct-GGUF", "Llama-3.2-3B-Instruct-Q4_K_M.gguf"),
            Origen("unsloth/Llama-3.2-3B-Instruct-GGUF", "Llama-3.2-3B-Instruct-Q4_K_M.gguf"),
            Origen("hugging-quants/Llama-3.2-3B-Instruct-Q4_K_M-GGUF", "llama-3.2-3b-instruct-q4_k_m.gguf"),
        ),
    )

    val QWEN_4B = ModeloDisponible(
        id = "qwen3-4b",
        nombre = "Qwen3 4B",
        familia = "Qwen",
        bytesAproximados = 2500L * 1024 * 1024,
        ramRecomendada = "8 GB",
        precision = 3,
        velocidad = 2,
        descripcion = "El más capaz de la lista: el que menos inventa y mejor sigue " +
            "instrucciones. Sólo tiene sentido en un teléfono con memoria de sobra.",
        origenes = qwen("4B", "Qwen3-4B-Q4_K_M.gguf"),
    )

    val GEMMA_4B = ModeloDisponible(
        id = "gemma3-4b",
        nombre = "Gemma 3 4B",
        familia = "Gemma",
        bytesAproximados = 2500L * 1024 * 1024,
        ramRecomendada = "8 GB",
        precision = 3,
        velocidad = 2,
        descripcion = "La alternativa grande de Google, muy sólida en español. " +
            "Mismas exigencias que el Qwen 4B.",
        origenes = listOf(
            Origen("ggml-org/gemma-3-4b-it-GGUF", "gemma-3-4b-it-Q4_K_M.gguf"),
            Origen("unsloth/gemma-3-4b-it-GGUF", "gemma-3-4b-it-Q4_K_M.gguf"),
            Origen("bartowski/google_gemma-3-4b-it-GGUF", "google_gemma-3-4b-it-Q4_K_M.gguf"),
        ),
    )

    val QWEN_8B = ModeloDisponible(
        id = "qwen3-8b",
        nombre = "Qwen3 8B",
        familia = "Qwen",
        bytesAproximados = 5000L * 1024 * 1024,
        ramRecomendada = "12 GB",
        precision = 4,
        velocidad = 1,
        descripcion = "Lo más capaz que entra en un teléfono. Razona de verdad y casi no " +
            "inventa. Necesita un equipo tope de gama y va a tardar bastante por " +
            "respuesta: pensalo como una consulta, no como un chat rápido.",
        origenes = qwen("8B", "Qwen3-8B-Q4_K_M.gguf"),
    )

    val LLAMA_8B = ModeloDisponible(
        id = "llama31-8b",
        nombre = "Llama 3.1 8B",
        familia = "Llama",
        bytesAproximados = 4920L * 1024 * 1024,
        ramRecomendada = "12 GB",
        precision = 4,
        velocidad = 1,
        descripcion = "El grande de Meta, muy sólido en español y con bastante " +
            "conocimiento propio. Mismas exigencias que el Qwen 8B.",
        origenes = listOf(
            Origen("bartowski/Meta-Llama-3.1-8B-Instruct-GGUF", "Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf"),
            Origen("unsloth/Meta-Llama-3.1-8B-Instruct-GGUF", "Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf"),
            Origen("hugging-quants/Meta-Llama-3.1-8B-Instruct-GGUF", "meta-llama-3.1-8b-instruct-q4_k_m.gguf"),
        ),
    )

    val GEMMA_12B = ModeloDisponible(
        id = "gemma3-12b",
        nombre = "Gemma 3 12B",
        familia = "Gemma",
        bytesAproximados = 7300L * 1024 * 1024,
        ramRecomendada = "16 GB",
        precision = 4,
        velocidad = 1,
        descripcion = "El techo absoluto de lo que corre en un teléfono, y sólo en los " +
            "de 16 GB. Si el tuyo no los tiene, Android va a cerrar la app al cargarlo: " +
            "Rama te avisa antes de intentarlo.",
        origenes = listOf(
            Origen("ggml-org/gemma-3-12b-it-GGUF", "gemma-3-12b-it-Q4_K_M.gguf"),
            Origen("unsloth/gemma-3-12b-it-GGUF", "gemma-3-12b-it-Q4_K_M.gguf"),
            Origen("bartowski/google_gemma-3-12b-it-GGUF", "google_gemma-3-12b-it-Q4_K_M.gguf"),
        ),
    )

    val PHI_MINI = ModeloDisponible(
        id = "phi4-mini",
        nombre = "Phi-4 mini",
        familia = "Phi",
        bytesAproximados = 2400L * 1024 * 1024,
        ramRecomendada = "8 GB",
        precision = 3,
        velocidad = 2,
        descripcion = "De Microsoft, entrenado con datos muy filtrados: rinde por encima " +
            "de su tamaño en razonamiento y matemática.",
        origenes = listOf(
            Origen("bartowski/microsoft_Phi-4-mini-instruct-GGUF", "microsoft_Phi-4-mini-instruct-Q4_K_M.gguf"),
            Origen("unsloth/Phi-4-mini-instruct-GGUF", "Phi-4-mini-instruct-Q4_K_M.gguf"),
            Origen("microsoft/Phi-4-mini-instruct-gguf", "phi-4-mini-instruct-q4.gguf"),
        ),
    )

    val QWEN_4B_PRECISO = ModeloDisponible(
        id = "qwen3-4b-q8",
        nombre = "Qwen3 4B · alta fidelidad",
        familia = "Qwen",
        bytesAproximados = 4300L * 1024 * 1024,
        ramRecomendada = "10 GB",
        precision = 4,
        velocidad = 2,
        descripcion = "Un 4B casi sin pérdida por compresión (Q8). Suele rendir mejor que " +
            "un 8B muy comprimido, y ocupa menos: si tenés 10 GB, es la mejor relación " +
            "de toda la lista.",
        origenes = qwen("4B", "Qwen3-4B-Q8_0.gguf"),
    )

    val MISTRAL_7B = ModeloDisponible(
        id = "mistral-7b",
        nombre = "Mistral 7B",
        familia = "Mistral",
        bytesAproximados = 4400L * 1024 * 1024,
        ramRecomendada = "12 GB",
        precision = 4,
        velocidad = 1,
        descripcion = "El clásico europeo: muy sólido en español y con buen criterio " +
            "para seguir instrucciones largas.",
        origenes = listOf(
            Origen("bartowski/Mistral-7B-Instruct-v0.3-GGUF", "Mistral-7B-Instruct-v0.3-Q4_K_M.gguf"),
            Origen("unsloth/mistral-7b-instruct-v0.3-GGUF", "mistral-7b-instruct-v0.3.Q4_K_M.gguf"),
            Origen("MaziyarPanahi/Mistral-7B-Instruct-v0.3-GGUF", "Mistral-7B-Instruct-v0.3.Q4_K_M.gguf"),
        ),
    )

    val QWEN_14B = ModeloDisponible(
        id = "qwen3-14b",
        nombre = "Qwen3 14B",
        familia = "Qwen",
        bytesAproximados = 9000L * 1024 * 1024,
        ramRecomendada = "16 GB",
        precision = 4,
        velocidad = 1,
        descripcion = "El techo absoluto: es el que más se acerca a una IA de escritorio. " +
            "Sólo en teléfonos de 16 GB, y con paciencia: cada respuesta puede tardar " +
            "más de un minuto.",
        origenes = qwen("14B", "Qwen3-14B-Q4_K_M.gguf"),
    )

    /** Del más liviano al más capaz: el orden en que conviene decidir. */
    val MODELOS: List<ModeloDisponible> = listOf(
        QWEN_06_R, QWEN_06_PRECISO, GEMMA_1B, LLAMA_1B, QWEN_17, LLAMA_3B, PHI_MINI,
        QWEN_4B, GEMMA_4B, QWEN_4B_PRECISO, MISTRAL_7B, LLAMA_8B, QWEN_8B, GEMMA_12B, QWEN_14B,
    )

    /** Los gigabytes de RAM que pide, como número. */
    fun ramNecesaria(modelo: ModeloDisponible): Int =
        modelo.ramRecomendada.filter { it.isDigit() }.toIntOrNull() ?: 4

    /** Compatibilidad con nombres viejos usados en el resto del código. */
    val CHICO = QWEN_06_R
    val MEDIANO = QWEN_17
    val RESPALDO = LLAMA_1B

    fun porId(id: String): ModeloDisponible? = MODELOS.firstOrNull { it.id == id }

    /** Nombre del archivo local donde se guarda cada modelo. */
    fun nombreLocal(modelo: ModeloDisponible): String = "${modelo.id}.gguf"
}
