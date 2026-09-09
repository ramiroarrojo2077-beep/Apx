package ar.rama.ai.motor

/** Dónde vive un archivo de modelo: repositorio y nombre dentro de él. */
data class Origen(val repositorio: String, val archivo: String)

/** Un modelo que Rama puede descargar y usar. */
data class ModeloDisponible(
    val id: String,
    val nombre: String,
    val familia: String,
    val bytesAproximados: Long,
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

    /**
     * Los gigabytes de RAM que hace falta tener para que ande.
     *
     * Se calcula del tamaño del archivo en vez de escribirse a mano, que era
     * la forma segura de que un día no coincidieran. La cuenta es archivo ×
     * 1,6 + 2 GB, y sale de dos cosas: los pesos tienen que quedar residentes
     * enteros —el modelo los recorre todos para cada palabra—, y arriba de eso
     * están la caché de atención, los buffers de cálculo y los tres y pico de
     * gigas que se lleva Android con sus cosas abiertas.
     *
     * De ahí sale el techo: con 8 GB de RAM entra un archivo de hasta unos
     * 3,7 GB, y ni un byte más. Un 8B comprimido a 4 bits pesa 5 y no entra;
     * comprimirlo hasta que entre lo deja peor que un 4B casi intacto.
     */
    val ramGb: Int
        get() {
            val gigas = bytesAproximados / 1024.0 / 1024 / 1024
            val pedido = gigas * 1.6 + 2.0
            return ESCALA_RAM.firstOrNull { it >= pedido } ?: TOPE_RAM
        }

    /** Cómo se muestra: "8 GB". */
    val ramRecomendada: String get() = "$ramGb GB"

    /** Si el teléfono no llega, la tarjeta lo marca y avisa antes de cargar. */
    fun entraEn(ramDelTelefono: Int): Boolean = ramDelTelefono == 0 || ramGb <= ramDelTelefono

    companion object {
        /** Cuántos tramos tiene el medidor de precisión y velocidad. */
        const val ESCALA = 4

        /**
         * El máximo que pide el modelo más pesado del catálogo.
         *
         * No es una preferencia: es hasta dónde llega un teléfono. Nada que
         * pida más entra en la lista, por capaz que sea.
         */
        const val TOPE_RAM = 8

        /** Los escalones reales de memoria de un teléfono. */
        val ESCALA_RAM = listOf(3, 4, 6, 8)
    }
}

/**
 * Los modelos que ofrece la app, del más liviano al más capaz.
 *
 * Son GGUF publicados por sus autores; Rama los descarga y los corre, no los
 * consulta por internet. La elección es siempre el mismo canje: más tamaño es
 * más precisión y menos velocidad.
 *
 * El catálogo termina en los 8 GB de RAM, que es hasta donde llega un teléfono
 * bueno de hoy. Antes había un 8B, un 12B y un 14B; se fueron porque eran una
 * promesa que casi nadie podía cobrar: al que no tenía la memoria le cerraban
 * la app, y al que la tenía le contestaban en más de un minuto. En su lugar
 * está la franja de alta fidelidad: los mismos 3B y 4B, pero guardados con
 * mucha menos compresión. Un 4B casi intacto le gana en la práctica a un 8B
 * apretado hasta que entre.
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

    val PHI_MINI = ModeloDisponible(
        id = "phi4-mini",
        nombre = "Phi-4 mini",
        familia = "Phi",
        bytesAproximados = 2400L * 1024 * 1024,
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

    // --------------------------------------------------------------------
    // La franja de arriba: 8 GB de RAM.
    //
    // Acá vive lo mejor que corre en un teléfono de verdad. No son modelos más
    // grandes sino los mismos, guardados con menos compresión: un 4B en Q6
    // conserva casi todo lo que sabía antes de comprimirse, mientras que un 8B
    // apretado hasta este tamaño pierde justo lo que lo hacía valer la pena.
    // --------------------------------------------------------------------

    val QWEN_4B_PRECISO = ModeloDisponible(
        id = "qwen3-4b-q6",
        nombre = "Qwen3 4B · alta fidelidad",
        familia = "Qwen",
        bytesAproximados = 3150L * 1024 * 1024,
        precision = 4,
        velocidad = 2,
        descripcion = "El mejor de la lista. Es el mismo Qwen3 4B pero casi sin pérdida " +
            "por compresión: acierta bastante más en datos y sigue mejor las " +
            "instrucciones largas. Si tu teléfono tiene 8 GB, es este.",
        origenes = qwen("4B", "Qwen3-4B-Q6_K.gguf"),
    )

    val GEMMA_4B_PRECISO = ModeloDisponible(
        id = "gemma3-4b-q6",
        nombre = "Gemma 3 4B · alta fidelidad",
        familia = "Gemma",
        bytesAproximados = 3370L * 1024 * 1024,
        precision = 4,
        velocidad = 2,
        descripcion = "El Gemma 4B sin apretar. Escribe con más soltura que el Qwen y " +
            "queda mejor para redactar; el Qwen le gana en datos y en matemática.",
        origenes = listOf(
            Origen("unsloth/gemma-3-4b-it-GGUF", "gemma-3-4b-it-Q6_K.gguf"),
            Origen("bartowski/google_gemma-3-4b-it-GGUF", "google_gemma-3-4b-it-Q6_K.gguf"),
            Origen("ggml-org/gemma-3-4b-it-GGUF", "gemma-3-4b-it-Q6_K.gguf"),
        ),
    )

    val LLAMA_3B_PRECISO = ModeloDisponible(
        id = "llama32-3b-q8",
        nombre = "Llama 3.2 3B · alta fidelidad",
        familia = "Llama",
        bytesAproximados = 3255L * 1024 * 1024,
        precision = 4,
        velocidad = 2,
        descripcion = "El 3B de Meta prácticamente intacto (Q8): de todo el catálogo es " +
            "el que menos perdió al comprimirse. Muy parejo en español.",
        origenes = listOf(
            Origen("bartowski/Llama-3.2-3B-Instruct-GGUF", "Llama-3.2-3B-Instruct-Q8_0.gguf"),
            Origen("unsloth/Llama-3.2-3B-Instruct-GGUF", "Llama-3.2-3B-Instruct-Q8_0.gguf"),
            Origen("hugging-quants/Llama-3.2-3B-Instruct-Q8_0-GGUF", "llama-3.2-3b-instruct-q8_0.gguf"),
        ),
    )

    val PHI_MINI_PRECISO = ModeloDisponible(
        id = "phi4-mini-q6",
        nombre = "Phi-4 mini · alta fidelidad",
        familia = "Phi",
        bytesAproximados = 3000L * 1024 * 1024,
        precision = 4,
        velocidad = 2,
        descripcion = "El Phi sin apretar. Es el que mejor razona paso a paso de los " +
            "cuatro, sobre todo con cuentas y con lógica.",
        origenes = listOf(
            Origen("bartowski/microsoft_Phi-4-mini-instruct-GGUF", "microsoft_Phi-4-mini-instruct-Q6_K.gguf"),
            Origen("unsloth/Phi-4-mini-instruct-GGUF", "Phi-4-mini-instruct-Q6_K.gguf"),
            Origen("bartowski/microsoft_Phi-4-mini-instruct-GGUF", "microsoft_Phi-4-mini-instruct-Q5_K_M.gguf"),
        ),
    )

    /** Del más liviano al más capaz: el orden en que conviene decidir. */
    val MODELOS: List<ModeloDisponible> = listOf(
        QWEN_06_R, QWEN_06_PRECISO, GEMMA_1B, LLAMA_1B, QWEN_17, LLAMA_3B, PHI_MINI,
        QWEN_4B, GEMMA_4B, PHI_MINI_PRECISO, QWEN_4B_PRECISO, LLAMA_3B_PRECISO, GEMMA_4B_PRECISO,
    )

    /** Los gigabytes de RAM que pide, como número. */
    fun ramNecesaria(modelo: ModeloDisponible): Int = modelo.ramGb

    /** Compatibilidad con nombres viejos usados en el resto del código. */
    val CHICO = QWEN_06_R
    val MEDIANO = QWEN_17
    val RESPALDO = LLAMA_1B

    fun porId(id: String): ModeloDisponible? = MODELOS.firstOrNull { it.id == id }

    /** Nombre del archivo local donde se guarda cada modelo. */
    fun nombreLocal(modelo: ModeloDisponible): String = "${modelo.id}.gguf"
}
