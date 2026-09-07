package ar.rama.ai

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ar.rama.ai.motor.AlmacenArchivo
import ar.rama.ai.motor.Memoria
import ar.rama.ai.motor.Paso
import ar.rama.ai.motor.Rama
import ar.rama.ai.motor.Respuesta
import ar.rama.ai.motor.Texto
import java.io.File
import java.util.concurrent.Executors

/**
 * Pantalla única de Rama AI: el chat.
 *
 * La interfaz se arma en código, sin XML de layout ni librerías de UI, para
 * que el APK quede mínimo y el build no dependa de nada externo.
 */
class MainActivity : Activity() {

    private lateinit var contenedorChat: LinearLayout
    private lateinit var scrollChat: ScrollView
    private lateinit var entrada: EditText
    private lateinit var subtitulo: TextView
    private lateinit var botonPensar: TextView
    private lateinit var botonEnviar: TextView

    private var rama: Rama? = null
    private var modoPensar = true
    private var ultimoAdjunto: Adjunto? = null

    private val trabajador = Executors.newSingleThreadExecutor()
    private val principal = Handler(Looper.getMainLooper())

    // -------------------------------------------------------- ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(construirPantalla())
        cargarCerebro()
    }

    override fun onDestroy() {
        trabajador.shutdownNow()
        super.onDestroy()
    }

    private fun cargarCerebro() {
        subtitulo.text = "despertando…"
        trabajador.execute {
            val conocimiento = assets.open("conocimiento.json").bufferedReader().use { it.readText() }
            val memoria = Memoria(AlmacenArchivo(File(filesDir, "aprendido.json")))
            val motor = Rama(conocimiento, memoria)
            principal.post {
                rama = motor
                subtitulo.text = "${motor.totalIntenciones} intenciones · ${motor.totalPatrones} patrones · " +
                    "${motor.tamanioVocabulario} rasgos"
                burbujaRama(
                    "¡Hola! Soy Rama, una mini IA que corre entera adentro de tu teléfono: " +
                        "sin internet, sin cuenta, sin nube.\n\n" +
                        "Preguntame algo, tocá 🧠 para verme pensar, o mandame una foto, un PDF o un video con ＋."
                )
                atenderArchivoCompartido()
            }
        }
    }

    // ------------------------------------------------------------ pantalla

    private fun construirPantalla(): View {
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Paleta.FONDO)
            fitsSystemWindows = true
        }
        raiz.addView(construirEncabezado())
        raiz.addView(construirDivisor())

        scrollChat = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
        }
        contenedorChat = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrollChat.addView(
            contenedorChat,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        raiz.addView(
            scrollChat,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        raiz.addView(construirSugerencias())
        raiz.addView(construirBarraEntrada())
        return raiz
    }

    private fun construirEncabezado(): View {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(14f), dp(12f), dp(12f))
        }

        val textos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val titulo = TextView(this).estilo(21f, Paleta.TEXTO, negrita = true)
        val nombre = SpannableString("Rama AI")
        nombre.setSpan(ForegroundColorSpan(Paleta.ACENTO), 5, 7, 0)
        titulo.text = nombre
        subtitulo = TextView(this).estilo(11.5f, Paleta.TENUE)
        textos.addView(titulo)
        textos.addView(subtitulo)
        fila.addView(textos, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        botonPensar = TextView(this).estilo(13f, Paleta.ACENTO_OSCURO, negrita = true).apply {
            text = "🧠 pensar"
            padding(dp(12f), dp(8f))
            setOnClickListener { alternarModoPensar() }
        }
        pintarBotonPensar()
        fila.addView(botonPensar)
        return fila
    }

    private fun alternarModoPensar() {
        modoPensar = !modoPensar
        pintarBotonPensar()
        avisar(
            if (modoPensar) "Modo pensar activado: te muestro cada paso de mi razonamiento."
            else "Modo pensar desactivado: sólo la respuesta."
        )
    }

    private fun pintarBotonPensar() {
        botonPensar.background = fondoPulsable(
            if (modoPensar) Paleta.PENSAR else Paleta.PANEL_ALTO,
            dp(20f).toFloat(),
            Paleta.BORDE,
            if (modoPensar) 0 else dp(1f),
        )
        botonPensar.setTextColor(if (modoPensar) 0xFF1A102B.toInt() else Paleta.TENUE)
    }

    private fun construirDivisor(): View = View(this).apply {
        setBackgroundColor(Paleta.BORDE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1f))
    }

    private fun construirSugerencias(): View {
        val carrusel = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(12f), 0, dp(12f), dp(4f))
        }
        val fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val ejemplos = listOf(
            "¿quién sos?", "¿cómo funcionás?", "cuánto es 12*7", "¿qué hora es?",
            "tirá un dado de 20", "contame un chiste", "aprende: mi color favorito = verde",
        )
        for (ejemplo in ejemplos) {
            val chip = TextView(this).estilo(12.5f, Paleta.TENUE).apply {
                text = ejemplo
                padding(dp(13f), dp(7f))
                background = fondoPulsable(Paleta.PANEL, dp(16f).toFloat(), Paleta.BORDE, dp(1f))
                setOnClickListener { enviar(ejemplo) }
            }
            val parametros = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { rightMargin = dp(7f) }
            fila.addView(chip, parametros)
        }
        carrusel.addView(fila)
        return carrusel
    }

    private fun construirBarraEntrada(): View {
        val barra = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(12f), dp(8f), dp(12f), dp(12f))
        }

        val adjuntar = TextView(this).estilo(20f, Paleta.TENUE, negrita = true).apply {
            text = "＋"
            gravity = Gravity.CENTER
            background = fondoPulsable(Paleta.PANEL, dp(22f).toFloat(), Paleta.BORDE, dp(1f))
            setOnClickListener { pedirArchivo() }
            contentDescription = "Adjuntar foto, PDF o video"
        }
        barra.addView(adjuntar, LinearLayout.LayoutParams(dp(44f), dp(44f)).apply { rightMargin = dp(8f) })

        entrada = EditText(this).apply {
            hint = "Escribí algo…"
            setHintTextColor(Paleta.TENUE)
            setTextColor(Paleta.TEXTO)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            background = fondoRedondeado(Paleta.PANEL, dp(22f).toFloat(), Paleta.BORDE, dp(1f))
            padding(dp(16f), dp(10f))
            maxLines = 4
            setHorizontallyScrolling(false)
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setOnEditorActionListener { _, accion, _ ->
                if (accion == EditorInfo.IME_ACTION_SEND) { enviar(text.toString()); true } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    botonEnviar.alpha = if (s.isNullOrBlank()) 0.45f else 1f
                }
            })
        }
        barra.addView(
            entrada,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )

        botonEnviar = TextView(this).estilo(19f, Paleta.ACENTO_OSCURO, negrita = true).apply {
            text = "↑"
            gravity = Gravity.CENTER
            alpha = 0.45f
            background = fondoPulsable(Paleta.ACENTO, dp(22f).toFloat())
            setOnClickListener { enviar(entrada.text.toString()) }
            contentDescription = "Enviar"
        }
        barra.addView(botonEnviar, LinearLayout.LayoutParams(dp(44f), dp(44f)).apply { leftMargin = dp(8f) })
        return barra
    }

    // ----------------------------------------------------------- mensajes

    private fun enviar(texto: String) {
        val limpio = texto.trim()
        if (limpio.isEmpty()) return
        val motor = rama
        if (motor == null) {
            avisar("Dame un segundo, todavía estoy cargando mi base de conocimiento.")
            return
        }
        entrada.setText("")
        burbujaUsuario(limpio)

        val bloque = if (modoPensar) bloquePensar() else null
        trabajador.execute {
            val respuesta = responderConContexto(motor, limpio)
            principal.post { mostrar(respuesta, bloque) }
        }
    }

    /**
     * Antes de ir al motor, damos una chance al último adjunto: si preguntás
     * por "la foto" o "el pdf", contestamos con lo que ya analizamos.
     */
    private fun responderConContexto(motor: Rama, texto: String): Respuesta {
        val adjunto = ultimoAdjunto
        val plano = Texto.normalizar(texto)
        if (adjunto != null && PREGUNTA_POR_ADJUNTO.containsMatchIn(plano)) {
            val pasos = listOf(
                Paso("Contexto", "la consulta menciona un adjunto y tengo «${adjunto.nombre}» analizado"),
                Paso("Análisis guardado", adjunto.pasos.joinToString("\n")),
                Paso("Decisión", "respondo con los datos que extraje del archivo, no con mi base de conocimiento"),
            )
            motor.memoria.registrarTurno("usuario", texto)
            motor.memoria.registrarTurno("rama", adjunto.resumen)
            return Respuesta(adjunto.resumen, "adjunto", 1.0, "adjunto", emptyList(), pasos)
        }
        return motor.responder(texto)
    }

    private fun mostrar(respuesta: Respuesta, bloque: BloquePensar?) {
        if (bloque == null) {
            burbujaRama(respuesta.texto)
            return
        }
        // Revelamos los pasos de a uno: el razonamiento es real, el ritmo es
        // para que se pueda leer.
        revelarPaso(bloque, respuesta.pasos, 0) {
            bloque.cerrar(respuesta)
            burbujaRama(respuesta.texto)
        }
    }

    private fun revelarPaso(bloque: BloquePensar, pasos: List<Paso>, indice: Int, alTerminar: () -> Unit) {
        if (indice >= pasos.size) { alTerminar(); return }
        bloque.agregarPaso(pasos[indice])
        alFinal()
        principal.postDelayed({ revelarPaso(bloque, pasos, indice + 1, alTerminar) }, RITMO_PENSAR)
    }

    private fun burbujaUsuario(texto: String) {
        val burbuja = TextView(this).estilo(15f, Color.WHITE).apply {
            text = texto
            padding(dp(14f), dp(10f))
            background = fondoRedondeado(
                Paleta.USUARIO, 0f,
                radios = esquinas(dp(16f).toFloat(), abajoDerecha = dp(4f).toFloat()),
            )
        }
        agregar(burbuja, Gravity.END)
    }

    private fun burbujaRama(texto: String): TextView {
        val burbuja = TextView(this).estilo(15f, Paleta.TEXTO).apply {
            text = texto
            padding(dp(14f), dp(11f))
            background = fondoRedondeado(
                Paleta.PANEL, 0f, Paleta.BORDE, dp(1f),
                radios = esquinas(dp(16f).toFloat(), abajoIzquierda = dp(4f).toFloat()),
            )
        }
        agregar(burbuja, Gravity.START)
        return burbuja
    }

    private fun avisar(texto: String) {
        val nota = TextView(this).estilo(12f, Paleta.TENUE).apply {
            text = texto
            gravity = Gravity.CENTER
            padding(dp(10f), dp(6f))
        }
        agregar(nota, Gravity.CENTER_HORIZONTAL, anchoMaximo = false)
    }

    private fun agregar(vista: View, alineacion: Int, anchoMaximo: Boolean = true) {
        val parametros = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = alineacion
            topMargin = dp(6f)
            bottomMargin = dp(6f)
        }
        if (anchoMaximo && vista is TextView) {
            vista.maxWidth = (resources.displayMetrics.widthPixels * 0.84f).toInt()
        }
        contenedorChat.addView(vista, parametros)
        alFinal()
    }

    private fun alFinal() {
        scrollChat.post { scrollChat.fullScroll(View.FOCUS_DOWN) }
    }

    private fun esquinas(
        radio: Float,
        abajoDerecha: Float = radio,
        abajoIzquierda: Float = radio,
    ): FloatArray = floatArrayOf(
        radio, radio, radio, radio, abajoDerecha, abajoDerecha, abajoIzquierda, abajoIzquierda,
    )

    // -------------------------------------------------------- modo pensar

    /** La tarjeta plegable donde Rama muestra su razonamiento. */
    private inner class BloquePensar(val tarjeta: LinearLayout, val cabecera: TextView, val detalle: LinearLayout) {

        fun agregarPaso(paso: Paso) {
            val titulo = TextView(this@MainActivity).estilo(11.5f, Paleta.PENSAR, negrita = true).apply {
                text = paso.titulo.uppercase()
                letterSpacing = 0.08f
            }
            val cuerpo = TextView(this@MainActivity).estilo(12.5f, Paleta.TENUE, monoespaciada = true).apply {
                text = paso.detalle
                setPadding(0, dp(2f), 0, dp(10f))
            }
            detalle.addView(titulo)
            detalle.addView(cuerpo)
        }

        fun cerrar(respuesta: Respuesta) {
            val n = respuesta.pasos.size
            cabecera.text = "🧠  $n pasos de razonamiento · tocá para ver"
            detalle.visibility = View.GONE
            tarjeta.setOnClickListener {
                val visible = detalle.visibility == View.VISIBLE
                detalle.visibility = if (visible) View.GONE else View.VISIBLE
                cabecera.text = if (visible) "🧠  $n pasos de razonamiento · tocá para ver"
                else "🧠  cómo lo pensé · tocá para ocultar"
                if (!visible) alFinal()
            }
        }
    }

    private fun bloquePensar(): BloquePensar {
        val tarjeta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = fondoRedondeado(Paleta.PANEL_ALTO, dp(14f).toFloat(), Paleta.BORDE, dp(1f))
            setPadding(dp(14f), dp(11f), dp(14f), dp(11f))
        }
        val cabecera = TextView(this).estilo(12.5f, Paleta.PENSAR, negrita = true).apply {
            text = "🧠  pensando…"
        }
        val detalle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10f), 0, 0)
        }
        tarjeta.addView(cabecera)
        tarjeta.addView(detalle)

        val parametros = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(6f)
            bottomMargin = dp(2f)
            rightMargin = dp(24f)
        }
        contenedorChat.addView(tarjeta, parametros)
        alFinal()
        return BloquePensar(tarjeta, cabecera, detalle)
    }

    // ----------------------------------------------------------- adjuntos

    /** Si llegamos por "Compartir" desde otra app, analizamos ese archivo. */
    private fun atenderArchivoCompartido() {
        if (intent?.action != Intent.ACTION_SEND) return
        @Suppress("DEPRECATION")
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return
        intent.removeExtra(Intent.EXTRA_STREAM)
        procesarAdjunto(uri)
    }

    private fun pedirArchivo() {
        val intencion = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*", "application/pdf"))
        }
        try {
            startActivityForResult(intencion, PEDIDO_ARCHIVO)
        } catch (e: Exception) {
            avisar("No encontré una app para elegir archivos en este teléfono.")
        }
    }

    override fun onActivityResult(codigo: Int, resultado: Int, datos: Intent?) {
        super.onActivityResult(codigo, resultado, datos)
        if (codigo != PEDIDO_ARCHIVO || resultado != RESULT_OK) return
        val uri = datos?.data ?: return
        procesarAdjunto(uri)
    }

    private fun procesarAdjunto(uri: Uri) {
        val cargando = TextView(this).estilo(13f, Paleta.TENUE).apply {
            text = "leyendo el archivo…"
            padding(dp(14f), dp(10f))
            background = fondoRedondeado(Paleta.PANEL, dp(14f).toFloat(), Paleta.BORDE, dp(1f))
        }
        agregar(cargando, Gravity.END)

        val bloque = if (modoPensar) bloquePensar() else null
        trabajador.execute {
            val adjunto = try {
                AnalizadorAdjuntos.analizar(this, uri)
            } catch (e: Exception) {
                null
            }
            principal.post {
                contenedorChat.removeView(cargando)
                if (adjunto == null) {
                    bloque?.cerrar(Respuesta("", pasos = listOf(Paso("Error", "no pude leer el archivo"))))
                    burbujaRama("No pude leer ese archivo. Puede que la app que lo comparte no me dé acceso.")
                    return@post
                }
                ultimoAdjunto = adjunto
                tarjetaAdjunto(adjunto)
                val pasos = adjunto.pasos.mapIndexed { i, texto ->
                    Paso(if (i == 0) "Lectura del archivo" else "Análisis ${i + 1}", texto)
                }
                val respuesta = Respuesta(adjunto.resumen, "adjunto", 1.0, "adjunto", emptyList(), pasos)
                if (bloque == null) {
                    burbujaRama(adjunto.resumen)
                    fichaDatos(adjunto.datos)
                } else {
                    revelarPaso(bloque, pasos, 0) {
                        bloque.cerrar(respuesta)
                        burbujaRama(adjunto.resumen)
                        fichaDatos(adjunto.datos)
                    }
                }
            }
        }
    }

    /** La tarjeta del archivo que mandó el usuario, con miniatura si la hay. */
    private fun tarjetaAdjunto(adjunto: Adjunto) {
        val tarjeta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = fondoRedondeado(Paleta.USUARIO, dp(16f).toFloat())
            setPadding(dp(6f), dp(6f), dp(6f), dp(8f))
        }

        val miniatura = adjunto.miniatura
        if (miniatura != null) {
            val imagen = ImageView(this).apply {
                setImageBitmap(miniatura)
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(vista: View, contorno: Outline) {
                        contorno.setRoundRect(0, 0, vista.width, vista.height, dp(11f).toFloat())
                    }
                }
            }
            val ancho = (resources.displayMetrics.widthPixels * 0.62f).toInt()
            val alto = (ancho * miniatura.height.toFloat() / miniatura.width).toInt()
                .coerceIn(dp(90f), dp(300f))
            tarjeta.addView(imagen, LinearLayout.LayoutParams(ancho, alto))
        }

        val icono = when (adjunto.tipo) {
            "imagen" -> "🖼"
            "video" -> "🎬"
            "pdf" -> "📄"
            else -> "📎"
        }
        val etiqueta = TextView(this).estilo(13f, Color.WHITE, negrita = true).apply {
            text = "$icono  ${adjunto.nombre}"
            setPadding(dp(8f), dp(8f), dp(8f), 0)
            maxLines = 2
        }
        val peso = TextView(this).estilo(11.5f, 0xCCFFFFFF.toInt()).apply {
            text = "${adjunto.tipo} · ${AnalizadorAdjuntos.pesoLegible(adjunto.tamanioBytes)}"
            setPadding(dp(8f), dp(1f), dp(8f), 0)
        }
        tarjeta.addView(etiqueta)
        tarjeta.addView(peso)

        val parametros = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.END
            topMargin = dp(6f)
            bottomMargin = dp(6f)
        }
        contenedorChat.addView(tarjeta, parametros)
        alFinal()
    }

    /** Tabla clave/valor con lo que se pudo extraer del archivo. */
    private fun fichaDatos(datos: List<Pair<String, String>>) {
        if (datos.isEmpty()) return
        val ficha = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = fondoRedondeado(Paleta.PANEL, dp(14f).toFloat(), Paleta.BORDE, dp(1f))
            setPadding(dp(14f), dp(11f), dp(14f), dp(11f))
        }
        for ((clave, valor) in datos) {
            val fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val etiqueta = TextView(this).estilo(12.5f, Paleta.TENUE).apply { text = clave }
            val contenido = TextView(this).estilo(12.5f, Paleta.TEXTO, monoespaciada = true).apply {
                text = valor
                gravity = Gravity.END
            }
            fila.addView(etiqueta, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            fila.addView(
                contenido,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f),
            )
            fila.setPadding(0, dp(3f), 0, dp(3f))
            ficha.addView(fila)
        }
        val parametros = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(2f)
            bottomMargin = dp(8f)
            rightMargin = dp(24f)
        }
        contenedorChat.addView(ficha, parametros)
        alFinal()
    }

    companion object {
        private const val PEDIDO_ARCHIVO = 1001
        private const val RITMO_PENSAR = 230L
        private val PREGUNTA_POR_ADJUNTO =
            Regex("\\b(archivo|foto|imagen|pdf|video|adjunt\\w*|documento)\\b")
    }
}
