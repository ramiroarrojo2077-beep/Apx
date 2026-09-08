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
import ar.rama.ai.motor.Asistente
import ar.rama.ai.motor.Generador
import ar.rama.ai.motor.Memoria
import ar.rama.ai.motor.Mensaje
import ar.rama.ai.motor.Modo
import ar.rama.ai.motor.Modos
import ar.rama.ai.motor.PasoAsistente
import ar.rama.ai.motor.Conversaciones
import ar.rama.ai.motor.Descargador
import ar.rama.ai.motor.Rama
import ar.rama.ai.motor.Respaldo
import ar.rama.ai.motor.Resultado
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

    private var asistente: Asistente? = null
    private var generador: Generador? = null
    private var modelos: PantallaModelos? = null
    private var chats: PantallaChats? = null
    private lateinit var conversaciones: Conversaciones
    private var chatActual: String = ""
    private lateinit var sugerencias: View
    private lateinit var avisoModelo: TextView
    private lateinit var chipModelo: TextView
    private val historial = mutableListOf<Mensaje>()
    private val chipsModo = mutableListOf<Pair<Modo, TextView>>()
    private var modoActual = Modos.PREDETERMINADO
    private var modoPensar = true
    private var generando = false
    private var animacionEscritura: Runnable? = null
    private var ultimoAdjunto: Adjunto? = null

    // Hilo demonio: si no lo fuera, seguiría vivo después de cerrar la
    // pantalla y mantendría el proceso (y la JVM de los tests) en pie.
    private val trabajador = Executors.newSingleThreadExecutor { tarea ->
        Thread(tarea, "rama-motor").apply { isDaemon = true }
    }
    private val principal = Handler(Looper.getMainLooper())

    // -------------------------------------------------------- ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instalarReporteDeErrores()
        modoActual = Modos.porId(preferencias().getString("modo", null))
        conversaciones = Conversaciones(File(filesDir, "chats"))
        chatActual = preferencias().getString("chat", null) ?: Conversaciones.nuevoId()

        val raiz = android.widget.FrameLayout(this)
        raiz.addView(construirPantalla())
        chats = PantallaChats(
            actividad = this,
            conversaciones = conversaciones,
            chatActual = { chatActual },
            alAbrir = { id -> abrirChat(id) },
            alNuevo = { nuevoChat() },
        ).also { raiz.addView(it.vista) }
        modelos = PantallaModelos(
            actividad = this,
            carpeta = File(filesDir, "modelos"),
            modeloActivo = { generador?.archivo },
            alUsar = { archivo -> cargarModelo(archivo) },
            alBorrar = { descargarModelo() },
            alImportar = { pedirModelo() },
        ).also { raiz.addView(it.vista) }
        setContentView(raiz)

        cargarCerebro()
    }

    /**
     * Deja el motivo del cierre escrito en disco.
     *
     * Si Rama se cae, Android mata el proceso y el usuario no ve nada: sólo
     * una app que "no responde". Guardamos el error para mostrarlo en el
     * próximo arranque, que es la única forma de enterarse sin cable ni adb.
     */
    private fun instalarReporteDeErrores() {
        val anterior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { hilo, error ->
            try {
                File(filesDir, ARCHIVO_ERROR).writeText(
                    "${java.util.Date()}\nhilo: ${hilo.name}\n\n${error.stackTraceToString()}"
                )
            } catch (e: Throwable) {
                // Si ni siquiera podemos escribir el error, no hay nada que hacer.
            }
            anterior?.uncaughtException(hilo, error)
        }
    }

    /** Si la vez pasada nos cerramos, contamos por qué. */
    private fun mostrarErrorAnterior() {
        val archivo = File(filesDir, ARCHIVO_ERROR)
        if (!archivo.exists()) return
        val detalle = try {
            archivo.readText()
        } catch (e: Exception) {
            return
        } finally {
            archivo.delete()
        }
        burbujaRama(
            "La vez pasada me cerré sola por un error. Te lo dejo tal cual, " +
                "para que se pueda arreglar:\n\n$detalle"
        )
    }

    override fun onPause() {
        super.onPause()
        guardarChat()
    }

    override fun onDestroy() {
        detenerAnimacion()
        generador?.cerrar()
        trabajador.shutdownNow()
        super.onDestroy()
    }

    private fun cargarCerebro() {
        subtitulo.text = "despertando…"
        principal.postDelayed({
            if (asistente == null) {
                subtitulo.text = "no pude cargar"
                burbujaRama(
                    "Algo me está trabando el arranque: pasaron ${ESPERA_ARRANQUE / 1000} segundos y " +
                        "todavía no cargué mi base de conocimiento. Probá cerrar y volver a abrir; " +
                        "si sigue igual, el error queda anotado y te lo muestro en el próximo arranque."
                )
            }
        }, ESPERA_ARRANQUE)
        enSegundoPlano("cargando mi base de conocimiento") {
            val conocimiento = assets.open("conocimiento.json").bufferedReader().use { it.readText() }
            // La enciclopedia es opcional: si falta, Rama arranca igual.
            val datos = try {
                assets.open("datos.json").bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                null
            }
            val memoria = Memoria(AlmacenArchivo(File(filesDir, "aprendido.json")))
            val motor = Rama(conocimiento, memoria, enciclopediaJson = datos)
            val ayudante = Asistente(motor)
            principal.post {
                ayudante.modo = modoActual
                asistente = ayudante
                val enciclopedia = motor.enciclopedia?.cantidad ?: 0
                subtitulo.text = "${motor.totalIntenciones + enciclopedia} temas · " +
                    "${motor.totalPatrones} patrones"

                // Si veníamos de una conversación, se retoma donde quedó.
                val guardado = conversaciones.cargar(chatActual)
                if (guardado.isNotEmpty()) abrirChat(chatActual) else saludar()
                actualizarSugerencias()

                actualizarAvisoModelo()
                mostrarErrorAnterior()
                restaurarModelo()
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
        raiz.addView(construirModos())

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

        avisoModelo = construirAvisoModelo()
        raiz.addView(avisoModelo)
        sugerencias = construirSugerencias()
        raiz.addView(sugerencias)
        raiz.addView(construirBarraEntrada())
        return raiz
    }

    private fun construirEncabezado(): View {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(14f), dp(12f), dp(12f))
        }

        val botonChats = TextView(this).estilo(17f, Paleta.TENUE).apply {
            text = "☰"
            gravity = Gravity.CENTER
            background = fondoPulsable(Paleta.PANEL, dp(19f).toFloat(), Paleta.BORDE, dp(1f))
            setOnClickListener { chats?.mostrar() }
            contentDescription = "Chats guardados"
        }
        fila.addView(
            botonChats,
            LinearLayout.LayoutParams(dp(38f), dp(38f)).apply { rightMargin = dp(10f) },
        )

        val textos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val titulo = TextView(this).estilo(21f, Paleta.TEXTO, negrita = true)
        val nombre = SpannableString("Rama AI")
        nombre.setSpan(ForegroundColorSpan(Paleta.ACENTO), 5, 7, 0)
        titulo.text = nombre
        subtitulo = TextView(this).estilo(11.5f, Paleta.TENUE)
        textos.addView(titulo)
        textos.addView(subtitulo)
        fila.addView(textos, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        chipModelo = TextView(this).estilo(12.5f, Paleta.TENUE).apply {
            text = "sin modelo"
            padding(dp(11f), dp(7f))
            background = fondoPulsable(Paleta.PANEL, dp(18f).toFloat(), Paleta.BORDE, dp(1f))
            setOnClickListener { modelos?.mostrar() }
            contentDescription = "Elegir modelo"
        }
        fila.addView(
            chipModelo,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { rightMargin = dp(7f) },
        )

        botonPensar = TextView(this).estilo(13f, Paleta.ACENTO_OSCURO, negrita = true).apply {
            text = "🧠"
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

    /**
     * La franja que avisa que falta el modelo.
     *
     * Sin modelo Rama sólo sabe lo que tiene escrito, y eso desconcierta: uno
     * pregunta cualquier cosa y no entiende por qué no contesta. Mejor decirlo
     * todo el tiempo, y que se pueda tocar para resolverlo.
     */
    private fun construirAvisoModelo(): TextView =
        TextView(this).estilo(12.5f, 0xFF1A1206.toInt(), negrita = true).apply {
            text = "⚠  Sin modelo cargado · sólo respondo lo que tengo escrito · tocá para descargar uno"
            gravity = Gravity.CENTER
            padding(dp(12f), dp(9f))
            background = fondoPulsable(0xFFFFB74D.toInt(), dp(10f).toFloat())
            setOnClickListener { modelos?.mostrar() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                leftMargin = dp(12f)
                rightMargin = dp(12f)
                bottomMargin = dp(6f)
            }
        }

    private fun actualizarAvisoModelo() {
        avisoModelo.visibility = if (generador == null) View.VISIBLE else View.GONE
    }

    /** La fila de modos: lo primero que se ve, porque cambia todo lo demás. */
    private fun construirModos(): View {
        val carrusel = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(12f), dp(9f), dp(12f), dp(3f))
        }
        val fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (modo in Modos.TODOS) {
            val chip = TextView(this).estilo(12.5f).apply {
                text = "${modo.icono} ${modo.nombre}"
                padding(dp(12f), dp(7f))
                setOnClickListener { elegirModo(modo) }
                contentDescription = "Modo ${modo.nombre}: ${modo.descripcion}"
            }
            chipsModo.add(modo to chip)
            fila.addView(
                chip,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { rightMargin = dp(7f) },
            )
        }
        carrusel.addView(fila)
        pintarModos()
        return carrusel
    }

    private fun elegirModo(modo: Modo) {
        modoActual = modo
        asistente?.modo = modo
        preferencias().edit().putString("modo", modo.id).apply()
        pintarModos()
        avisar("${modo.icono} ${modo.nombre} · ${modo.descripcion}")
    }

    private fun pintarModos() {
        for ((modo, chip) in chipsModo) {
            val activo = modo.id == modoActual.id
            chip.background = fondoPulsable(
                if (activo) Paleta.ACENTO else Paleta.PANEL,
                dp(16f).toFloat(),
                Paleta.BORDE,
                if (activo) 0 else dp(1f),
            )
            chip.setTextColor(if (activo) Paleta.ACENTO_OSCURO else Paleta.TENUE)
        }
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
            // Sin la bandera MULTI_LINE el teclado muestra "enviar" en lugar de
            // un salto de línea, que es lo que uno espera en un chat.
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, accion, evento ->
                val enter = evento?.keyCode == android.view.KeyEvent.KEYCODE_ENTER
                if (accion == EditorInfo.IME_ACTION_SEND || accion == EditorInfo.IME_ACTION_DONE || enter) {
                    enviar(text.toString())
                    true
                } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    pintarBotonEnviar()
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
            setOnClickListener {
                if (generando) {
                    generando = false
                    asistente?.cancelar()
                    pintarBotonEnviar()
                } else {
                    enviar(entrada.text.toString())
                }
            }
            contentDescription = "Enviar"
        }
        barra.addView(botonEnviar, LinearLayout.LayoutParams(dp(44f), dp(44f)).apply { leftMargin = dp(8f) })
        return barra
    }

    // ----------------------------------------------------------- mensajes

    private fun enviar(texto: String) {
        val limpio = texto.trim()
        if (limpio.isEmpty()) return
        if (generando) {
            avisar("Esperá que termine de escribir, o tocá ✕ para cortarla.")
            return
        }
        val ayudante = asistente
        if (ayudante == null) {
            avisar("Dame un segundo, todavía estoy cargando.")
            return
        }
        entrada.setText("")
        burbujaUsuario(limpio)

        val adjunto = ultimoAdjunto
        if (adjunto != null && PREGUNTA_POR_ADJUNTO.containsMatchIn(Texto.normalizar(limpio))) {
            responderSobreAdjunto(adjunto, limpio)
            return
        }

        val bloque = if (modoPensar) bloquePensar() else null
        val burbuja = burbujaRama("")
        animarEscritura(burbuja)
        val acumulado = StringBuilder()
        generando = true
        pintarBotonEnviar()

        val turnos = historial.toList()
        historial.add(Mensaje("user", limpio))
        actualizarSugerencias()

        enSegundoPlano("pensando la respuesta") {
            val respuesta = ayudante.responder(
                pregunta = limpio,
                historial = turnos,
                alPaso = { paso -> principal.post { bloque?.agregarPaso(paso.titulo, paso.detalle); alFinal() } },
            ) { fragmento ->
                principal.post {
                    detenerAnimacion()
                    acumulado.append(fragmento)
                    burbuja.text = acumulado
                    alFinal()
                }
                generando
            }

            principal.post {
                detenerAnimacion()
                generando = false
                pintarBotonEnviar()
                val texto = respuesta.texto
                burbuja.text = texto
                historial.add(Mensaje("assistant", texto))
                bloque?.cerrar(respuesta.pasos.size)
                selloRespaldo(respuesta.respaldo)
                if (respuesta.fuentesWeb.isNotEmpty()) fichaFuentes(respuesta.fuentesWeb)
                guardarChat()
                alFinal()
            }
        }
    }

    /** Preguntas sobre el último archivo: se contestan con lo ya analizado. */
    private fun responderSobreAdjunto(adjunto: Adjunto, pregunta: String) {
        val bloque = if (modoPensar) bloquePensar() else null
        bloque?.agregarPaso("Contexto", "tu pregunta menciona un adjunto y tengo «${adjunto.nombre}» analizado")
        bloque?.agregarPaso("Análisis guardado", adjunto.pasos.joinToString("\n"))
        bloque?.cerrar(2)
        burbujaRama(adjunto.resumen)
        historial.add(Mensaje("user", pregunta))
        historial.add(Mensaje("assistant", adjunto.resumen))
        guardarChat()
    }

    /**
     * Debajo de cada respuesta, con qué se respaldó.
     *
     * Un modelo chico escribe con la misma seguridad un dato verificado y una
     * invención. Esto es lo que le devuelve al usuario la posibilidad de
     * distinguirlas de un vistazo.
     */
    private fun selloRespaldo(respaldo: Respaldo) {
        val color = when (respaldo) {
            Respaldo.CALCULO -> Paleta.ACENTO
            Respaldo.WEB -> Paleta.ACENTO
            Respaldo.BASE -> Paleta.TENUE
            Respaldo.SOLO_MODELO -> 0xFFFFB74D.toInt()
        }
        val icono = when (respaldo) {
            Respaldo.CALCULO -> "🧮"
            Respaldo.WEB -> "🌐"
            Respaldo.BASE -> "📗"
            Respaldo.SOLO_MODELO -> "⚠"
        }
        val sello = TextView(this).estilo(11.5f, color).apply {
            text = "$icono  ${respaldo.etiqueta}"
            padding(dp(9f), dp(4f))
            background = fondoRedondeado(Paleta.PANEL, dp(9f).toFloat(), Paleta.BORDE, dp(1f))
            setOnClickListener { avisar(respaldo.explicacion) }
            contentDescription = respaldo.explicacion
        }
        val parametros = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.START
            leftMargin = dp(4f)
            bottomMargin = dp(6f)
        }
        contenedorChat.addView(sello, parametros)
        alFinal()
    }

    /** Las fuentes que consultó, como tarjeta aparte y tocable. */
    private fun fichaFuentes(fuentes: List<Resultado>) {
        val ficha = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = fondoRedondeado(Paleta.PANEL, dp(14f).toFloat(), Paleta.BORDE, dp(1f))
            setPadding(dp(14f), dp(11f), dp(14f), dp(11f))
        }
        ficha.addView(
            TextView(this).estilo(11.5f, Paleta.TENUE, negrita = true).apply {
                text = "FUENTES CONSULTADAS"
                letterSpacing = 0.08f
                setPadding(0, 0, 0, dp(6f))
            }
        )
        for (fuente in fuentes) {
            val item = TextView(this).estilo(12.5f, Paleta.TEXTO).apply {
                text = "${fuente.titulo}\n${fuente.url}"
                setPadding(0, dp(4f), 0, dp(4f))
                setOnClickListener { abrirEnlace(fuente.url) }
            }
            ficha.addView(item)
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

    private fun abrirEnlace(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            avisar("No pude abrir el enlace.")
        }
    }

    private fun pintarBotonEnviar() {
        botonEnviar.text = if (generando) "✕" else "↑"
        botonEnviar.alpha = if (generando || entrada.text.isNotBlank()) 1f else 0.45f
    }

    // ------------------------------------------------------------ modelo

    private fun restaurarModelo() {
        val guardado = preferencias().getString("modelo", null) ?: return
        val archivo = File(guardado)
        if (archivo.exists()) cargarModelo(archivo) else chipModelo.text = "sin modelo"
    }

    /**
     * Si el modelo no entra en la memoria libre, el sistema mata la app sin
     * decir nada. Es preferible avisarlo antes que cerrarse de golpe.
     */
    private fun memoriaAlcanza(archivo: File): Boolean {
        return try {
            val gestor = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            val info = android.app.ActivityManager.MemoryInfo()
            gestor.getMemoryInfo(info)
            info.availMem > archivo.length() * 11 / 10
        } catch (e: Exception) {
            true  // sin datos, dejamos intentar
        }
    }

    private fun cargarModelo(archivo: File) {
        if (!memoriaAlcanza(archivo)) {
            val gestor = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            val info = android.app.ActivityManager.MemoryInfo()
            gestor.getMemoryInfo(info)
            burbujaRama(
                "«${archivo.name}» pesa ${AnalizadorAdjuntos.pesoLegible(archivo.length())} y ahora " +
                    "mismo hay ${AnalizadorAdjuntos.pesoLegible(info.availMem)} de memoria libre. " +
                    "Si lo cargo, el sistema va a cerrar la app.\n\n" +
                    "Cerrá otras aplicaciones y probá de nuevo, o usá el modelo chico."
            )
            chipModelo.text = "sin modelo"
            return
        }
        chipModelo.text = "cargando…"
        enSegundoPlano("cargando el modelo") {
            generador?.cerrar()
            val abierto = Generador.abrir(archivo)
            principal.post {
                generador = abierto
                asistente?.generador = abierto
                if (abierto == null) {
                    chipModelo.text = "sin modelo"
                    burbujaRama(
                        "No pude cargar «${archivo.name}». Puede que el archivo esté incompleto, " +
                            "que no sea un GGUF, o que al teléfono le falte memoria para este modelo."
                    )
                } else {
                    preferencias().edit().putString("modelo", archivo.absolutePath).apply()
                    chipModelo.text = archivo.nameWithoutExtension
                    burbujaRama("Modelo cargado: ${abierto.info}\n\nYa puedo escribir respuestas propias.")
                }
                actualizarAvisoModelo()
                modelos?.refrescar()
            }
        }
    }

    private fun descargarModelo() {
        generador?.cerrar()
        generador = null
        asistente?.generador = null
        preferencias().edit().remove("modelo").apply()
        chipModelo.text = "sin modelo"
        actualizarAvisoModelo()
    }

    private fun pedirModelo() {
        val intencion = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            startActivityForResult(intencion, PEDIDO_MODELO)
        } catch (e: Exception) {
            avisar("No encontré una app para elegir archivos.")
        }
    }

    private fun preferencias() = getSharedPreferences("rama", MODE_PRIVATE)

    // ------------------------------------------------------------- chats

    /** Se guarda solo, después de cada intercambio. No hay botón de guardar. */
    private fun guardarChat() {
        if (historial.isEmpty()) return
        try {
            conversaciones.guardar(chatActual, historial.toList())
            preferencias().edit().putString("chat", chatActual).apply()
        } catch (e: Exception) {
            avisar("No pude guardar el chat: ${e.message}")
        }
    }

    private fun nuevoChat() {
        guardarChat()
        chatActual = Conversaciones.nuevoId()
        historial.clear()
        ultimoAdjunto = null
        contenedorChat.removeAllViews()
        preferencias().edit().putString("chat", chatActual).apply()
        actualizarSugerencias()
        saludar()
    }

    private fun abrirChat(id: String) {
        guardarChat()
        val mensajes = conversaciones.cargar(id)
        chatActual = id
        historial.clear()
        historial.addAll(mensajes)
        ultimoAdjunto = null
        contenedorChat.removeAllViews()
        preferencias().edit().putString("chat", id).apply()

        for (mensaje in mensajes) {
            if (mensaje.rol == "user") burbujaUsuario(mensaje.contenido)
            else burbujaRama(mensaje.contenido)
        }
        actualizarSugerencias()
        alFinal()
    }

    /** Las sugerencias sólo estorban cuando la conversación ya arrancó. */
    private fun actualizarSugerencias() {
        sugerencias.visibility = if (historial.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun saludar() {
        burbujaRama(
            "¡Hola! Soy Rama. Corro entera adentro de tu teléfono: el modelo que escribe " +
                "mis respuestas es mío y local, no consulto la IA de nadie.\n\n" +
                "Los modos de arriba cambian cómo escribo, ☰ guarda y abre tus chats, y " +
                "siempre contesto en español."
        )
    }

    /** Tres puntitos que laten mientras el modelo arranca. */
    private fun animarEscritura(burbuja: TextView) {
        detenerAnimacion()
        var paso = 0
        val latido = object : Runnable {
            override fun run() {
                burbuja.text = "·".repeat(1 + paso % 3)
                paso++
                principal.postDelayed(this, 350)
            }
        }
        animacionEscritura = latido
        principal.post(latido)
    }

    private fun detenerAnimacion() {
        animacionEscritura?.let { principal.removeCallbacks(it) }
        animacionEscritura = null
    }

    private fun copiar(texto: String) {
        try {
            val portapapeles = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            portapapeles.setPrimaryClip(android.content.ClipData.newPlainText("Rama", texto))
            avisar("Copiado al portapapeles")
        } catch (e: Exception) {
            avisar("No pude copiar")
        }
    }

    /**
     * Copia el .gguf elegido a la carpeta de la app.
     *
     * llama.cpp necesita una ruta real del sistema de archivos; un content://
     * del selector no le sirve, así que hay que traerlo.
     */
    private fun importarModelo(uri: Uri) {
        modelos?.ocultar()
        burbujaRama("Copiando el modelo al almacenamiento de la app…")
        enSegundoPlano("importando el modelo") {
            val carpeta = File(filesDir, "modelos").apply { mkdirs() }
            val destino = File(carpeta, "importado.gguf")
            var copiados = 0L
            contentResolver.openInputStream(uri).use { entrada ->
                if (entrada == null) throw java.io.IOException("no pude abrir el archivo elegido")
                destino.outputStream().use { salida -> copiados = entrada.copyTo(salida) }
            }
            principal.post {
                if (!Descargador.esGguf(destino)) {
                    destino.delete()
                    burbujaRama("Ese archivo no es un modelo GGUF. Fijate que la extensión sea .gguf.")
                } else {
                    burbujaRama("Copiado (${AnalizadorAdjuntos.pesoLegible(copiados)}). Cargándolo…")
                    cargarModelo(destino)
                }
            }
        }
    }

    /**
     * Corre algo fuera del hilo principal sin que un error se lo lleve puesto.
     *
     * En Android una excepción en un hilo cualquiera tumba el proceso entero:
     * la app se cerraría sin decir por qué. Acá la atajamos y la mostramos en
     * el chat, que es donde el usuario puede verla.
     */
    private fun enSegundoPlano(queEstabaHaciendo: String, tarea: () -> Unit) {
        trabajador.execute {
            try {
                tarea()
            } catch (e: Throwable) {
                principal.post {
                    subtitulo.text = "algo falló"
                    burbujaRama(
                        "Me tropecé $queEstabaHaciendo:\n\n" +
                            "${e.javaClass.simpleName}: ${e.message ?: "sin detalle"}"
                    )
                }
            }
        }
    }

    private fun burbujaUsuario(texto: String) {
        val burbuja = TextView(this).estilo(15f, Color.WHITE).apply {
            text = texto
            padding(dp(14f), dp(10f))
            setOnLongClickListener { copiar(texto); true }
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
            setOnLongClickListener { copiar(this.text.toString()); true }
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

        fun agregarPaso(tituloPaso: String, detallePaso: String) {
            val titulo = TextView(this@MainActivity).estilo(11.5f, Paleta.PENSAR, negrita = true).apply {
                text = tituloPaso.uppercase()
                letterSpacing = 0.08f
            }
            val cuerpo = TextView(this@MainActivity).estilo(12.5f, Paleta.TENUE, monoespaciada = true).apply {
                text = detallePaso
                setPadding(0, dp(2f), 0, dp(10f))
            }
            detalle.addView(titulo)
            detalle.addView(cuerpo)
        }

        fun cerrar(n: Int) {
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
        if (resultado != RESULT_OK) return
        val uri = datos?.data ?: return
        when (codigo) {
            PEDIDO_ARCHIVO -> procesarAdjunto(uri)
            PEDIDO_MODELO -> importarModelo(uri)
        }
    }

    private fun procesarAdjunto(uri: Uri) {
        val cargando = TextView(this).estilo(13f, Paleta.TENUE).apply {
            text = "leyendo el archivo…"
            padding(dp(14f), dp(10f))
            background = fondoRedondeado(Paleta.PANEL, dp(14f).toFloat(), Paleta.BORDE, dp(1f))
        }
        agregar(cargando, Gravity.END)

        val bloque = if (modoPensar) bloquePensar() else null
        enSegundoPlano("leyendo el archivo") {
            val adjunto = try {
                AnalizadorAdjuntos.analizar(this, uri)
            } catch (e: Exception) {
                null
            }
            principal.post {
                contenedorChat.removeView(cargando)
                if (adjunto == null) {
                    bloque?.agregarPaso("Error", "no pude leer el archivo")
                    bloque?.cerrar(1)
                    burbujaRama("No pude leer ese archivo. Puede que la app que lo comparte no me dé acceso.")
                    return@post
                }
                ultimoAdjunto = adjunto
                tarjetaAdjunto(adjunto)
                adjunto.pasos.forEachIndexed { i, texto ->
                    bloque?.agregarPaso(if (i == 0) "Lectura del archivo" else "Análisis ${i + 1}", texto)
                }
                bloque?.cerrar(adjunto.pasos.size)
                burbujaRama(adjunto.resumen)
                fichaDatos(adjunto.datos)
                historial.add(Mensaje("assistant", adjunto.resumen))
                guardarChat()
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
        private const val PEDIDO_MODELO = 1002
        private const val ARCHIVO_ERROR = "ultimo-error.txt"
        private const val ESPERA_ARRANQUE = 8000L
        private const val RITMO_PENSAR = 230L
        private val PREGUNTA_POR_ADJUNTO =
            Regex("\\b(archivo|foto|imagen|pdf|video|adjunt\\w*|documento)\\b")
    }
}
