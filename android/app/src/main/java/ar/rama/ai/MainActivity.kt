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
    private lateinit var botonPensar: ImageView
    private lateinit var botonEnviar: ImageView

    private var asistente: Asistente? = null
    private var generador: Generador? = null
    private var modelos: PantallaModelos? = null
    private var chats: PantallaChats? = null
    private lateinit var conversaciones: Conversaciones
    private var chatActual: String = ""
    private lateinit var avisoModelo: View
    private lateinit var chipModelo: TextView
    private lateinit var puntoModelo: View
    /** La portada del chat vacío. Se va apenas se escribe la primera pregunta. */
    private var bienvenida: View? = null
    /** Los tres puntos que laten mientras el modelo todavía no soltó nada. */
    private var indicador: View? = null
    private val historial = mutableListOf<Mensaje>()
    private val chipsModo = mutableListOf<Pair<Modo, TextView>>()
    private var modoActual = Modos.PREDETERMINADO
    private var modoPensar = true
    private var generando = false
    private var modeloEnPausa = false
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
            modeloActivo = { generador?.archivo },
            alUsar = { archivo -> cargarModelo(archivo) },
            alBorrar = { descargarModelo() },
            alImportar = { pedirModelo() },
            alCopiar = { enlace ->
                copiar(enlace)
                avisar("Enlace copiado. Pegalo en el navegador, bajá el .gguf y volvé a importarlo.")
            },
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

    /**
     * Suelta el modelo cuando el sistema se queda sin memoria.
     *
     * Un modelo grande son varios gigas ocupados. Si Android tiene que elegir
     * entre matar la app o que le devolvamos memoria, conviene devolverla: se
     * recarga sola en la siguiente pregunta. Perder unos segundos es mejor que
     * perder la conversación.
     */
    override fun onTrimMemory(nivel: Int) {
        super.onTrimMemory(nivel)
        val apreta = nivel == TRIM_MEMORY_COMPLETE ||
            nivel == TRIM_MEMORY_MODERATE ||
            nivel == TRIM_MEMORY_RUNNING_CRITICAL
        if (apreta && !generando && generador != null) {
            generador?.cerrar()
            generador = null
            asistente?.generador = null
            modeloEnPausa = true
            principal.post {
                chipModelo.text = "modelo en pausa"
                pintarEstadoModelo()
            }
        }
    }

    /**
     * Vuelve a cargar el modelo si lo soltamos por falta de memoria.
     * Devuelve false si hay que esperar: la pregunta se reenvía sola después.
     */
    private fun asegurarModelo(pregunta: String): Boolean {
        if (generador != null || !modeloEnPausa) return true
        val guardado = preferencias().getString("modelo", null) ?: return true
        val archivo = File(guardado)
        if (!archivo.exists()) {
            modeloEnPausa = false
            return true
        }

        avisar("Recargando el modelo, que había soltado por falta de memoria…")
        enSegundoPlano("recargando el modelo") {
            val abierto = Generador.abrir(archivo)
            principal.post {
                generador = abierto
                asistente?.generador = abierto
                modeloEnPausa = false
                chipModelo.text = archivo.nameWithoutExtension
                actualizarAvisoModelo()
                if (abierto != null) enviar(pregunta) else burbujaRama("No pude recargar el modelo.")
            }
        }
        return false
    }

    override fun onDestroy() {
        quitarPuntos()
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
        raiz.addView(divisor())
        raiz.addView(construirModos())

        scrollChat = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            setPadding(dp(Espacio.L), dp(Espacio.S), dp(Espacio.L), dp(Espacio.S))
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
        raiz.addView(construirBarraEntrada())
        return raiz
    }

    // -------------------------------------------------------- encabezado

    private fun construirEncabezado(): View {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(Espacio.M), dp(Espacio.M - 2f), dp(Espacio.M), dp(Espacio.M - 2f))
        }

        fila.addView(
            botonIcono(Iconos.chats(), "Chats guardados") { chats?.mostrar() },
            LinearLayout.LayoutParams(dp(40f), dp(40f)).apply { rightMargin = dp(Espacio.M) },
        )

        val textos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val titulo = TextView(this).estilo(Tipo.SUBTITULO + 2f, Paleta.TEXTO, negrita = true, interlineado = 1f).apply {
            text = "Rama"
            letterSpacing = -0.01f
        }
        subtitulo = TextView(this).estilo(Tipo.MICRO, Paleta.TEXTO_3, interlineado = 1f).apply {
            setPadding(0, dp(3f), 0, 0)
        }
        textos.addView(titulo)
        textos.addView(subtitulo)
        fila.addView(textos, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        fila.addView(construirChipModelo(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { rightMargin = dp(Espacio.S) })

        botonPensar = botonIcono(
            Iconos.destello(), "Ver el razonamiento", relleno = true, tamanioIcono = 17f,
        ) { alternarModoPensar() }
        pintarBotonPensar()
        fila.addView(botonPensar)
        return fila
    }

    /**
     * El estado del modelo, dicho en el menor espacio posible.
     *
     * Un punto de color y un nombre: verde si está cargado, ámbar si falta.
     * Es la información que más se consulta y la que menos lugar tiene.
     */
    private fun construirChipModelo(): View {
        val chip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            padding(dp(Espacio.M - 2f), dp(7f))
            background = fondoPulsable(Paleta.SUPERFICIE, dp(Radio.PILDORA).toFloat(), Paleta.BORDE, dp(1f))
            setOnClickListener { modelos?.mostrar() }
            contentDescription = "Elegir modelo"
        }
        puntoModelo = punto(Paleta.AVISO, 7f)
        chip.addView(puntoModelo, LinearLayout.LayoutParams(dp(7f), dp(7f)).apply { rightMargin = dp(7f) })
        chipModelo = TextView(this).estilo(Tipo.ETIQUETA, Paleta.TEXTO_2, interlineado = 1f).apply {
            text = "sin modelo"
            maxWidth = dp(96f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        chip.addView(chipModelo)
        return chip
    }

    /** El punto del chip sigue al estado real, sin que haya que leer nada. */
    private fun pintarEstadoModelo(cargando: Boolean = false) {
        val color = when {
            cargando || modeloEnPausa -> Paleta.PENSAR
            generador != null -> Paleta.ACENTO
            else -> Paleta.AVISO
        }
        puntoModelo.background = fondoRedondeado(color, dp(3.5f).toFloat())
    }

    private fun alternarModoPensar() {
        modoPensar = !modoPensar
        pintarBotonPensar()
        avisar(
            if (modoPensar) "Razonamiento visible: te muestro cada paso."
            else "Razonamiento oculto: sólo la respuesta."
        )
    }

    private fun pintarBotonPensar() {
        val activo = modoPensar
        botonPensar.setImageDrawable(
            Icono(Iconos.destello(), if (activo) Paleta.PENSAR else Paleta.TEXTO_3, relleno = true)
        )
        botonPensar.background = fondoPulsable(
            if (activo) Paleta.PENSAR_TENUE else Paleta.SUPERFICIE,
            dp(20f).toFloat(),
            if (activo) Paleta.PENSAR else Paleta.BORDE,
            dp(1f),
        )
    }

    /**
     * El aviso de que falta el modelo.
     *
     * Antes era una franja naranja de lado a lado, que es como avisa un
     * navegador de que algo se rompió. Acá no se rompió nada: falta un paso.
     * Una tarjeta con una franja ámbar al costado dice lo mismo sin gritar.
     */
    private fun construirAvisoModelo(): View {
        val tarjeta = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = fondoConFranja(Paleta.AVISO_TENUE, Paleta.AVISO, dp(Radio.MEDIO).toFloat(), dp(3f))
            setPadding(dp(Espacio.M + 2f), dp(Espacio.M - 2f), dp(Espacio.M), dp(Espacio.M - 2f))
            setOnClickListener { modelos?.mostrar() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                leftMargin = dp(Espacio.M)
                rightMargin = dp(Espacio.M)
                bottomMargin = dp(Espacio.S)
            }
        }
        tarjeta.addView(
            ImageView(this).apply { setImageDrawable(Icono(Iconos.advertencia(), Paleta.AVISO)) },
            LinearLayout.LayoutParams(dp(17f), dp(17f)).apply { rightMargin = dp(Espacio.M - 2f) },
        )
        val textos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textos.addView(
            TextView(this).estilo(Tipo.SECUNDARIO, Paleta.TEXTO, negrita = true, interlineado = 1f).apply {
                text = "Todavía no hay modelo"
            }
        )
        textos.addView(
            TextView(this).estilo(Tipo.MICRO + 0.5f, Paleta.TEXTO_2, interlineado = 1.2f).apply {
                text = "Respondo con lo que tengo escrito. Tocá para elegir uno."
                setPadding(0, dp(2f), 0, 0)
            }
        )
        tarjeta.addView(textos, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return tarjeta
    }

    private fun actualizarAvisoModelo() {
        avisoModelo.visibility = if (generador == null) View.VISIBLE else View.GONE
        pintarEstadoModelo()
    }

    // ------------------------------------------------------------- modos

    /** La fila de modos: lo primero que se ve, porque cambia todo lo demás. */
    private fun construirModos(): View {
        val carrusel = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(Espacio.M), dp(Espacio.S), dp(Espacio.M), dp(Espacio.S))
        }
        val fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (modo in Modos.TODOS) {
            // Sin el emoji: el nombre solo se lee más rápido y se ve igual en
            // todos los teléfonos.
            val chip = TextView(this).estilo(Tipo.ETIQUETA + 0.5f, interlineado = 1f).apply {
                text = modo.nombre
                padding(dp(Espacio.M + 2f), dp(Espacio.S - 1f))
                setOnClickListener { elegirModo(modo) }
                contentDescription = "Modo ${modo.nombre}: ${modo.descripcion}"
            }
            chipsModo.add(modo to chip)
            fila.addView(
                chip,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { rightMargin = dp(Espacio.S - 1f) },
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
        avisar("${modo.nombre} · ${modo.descripcion}")
    }

    /**
     * El modo activo se marca con el acento diluido, no pintado entero.
     *
     * Un chip verde lleno le saca protagonismo a la conversación, que es lo
     * único que el usuario vino a leer.
     */
    private fun pintarModos() {
        for ((modo, chip) in chipsModo) {
            val activo = modo.id == modoActual.id
            chip.background = fondoPulsable(
                if (activo) Paleta.ACENTO_TENUE else Paleta.SUPERFICIE,
                dp(Radio.PILDORA).toFloat(),
                if (activo) Paleta.ACENTO else Paleta.BORDE,
                dp(1f),
            )
            chip.setTextColor(if (activo) Paleta.ACENTO else Paleta.TEXTO_2)
            chip.setTypeface(null, if (activo) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    // -------------------------------------------------------- bienvenida

    /**
     * La portada del chat vacío.
     *
     * Un chat en blanco no dice qué es esto ni qué se le puede pedir. Antes lo
     * resolvía un párrafo de saludo dentro de una burbuja, que se leía como si
     * Rama ya estuviera hablando sola. Una portada con la marca y cuatro
     * ejemplos tocables cumple la misma función y desaparece sin dejar rastro
     * apenas empieza la conversación.
     */
    private fun construirBienvenida(): View {
        val columna = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(Espacio.XS), dp(Espacio.XL), dp(Espacio.XS), dp(Espacio.S))
        }

        val emblema = ImageView(this).apply {
            setImageDrawable(Icono(Iconos.marca(), Paleta.ACENTO, grosor = 1.7f))
            val margen = dp(15f)
            setPadding(margen, margen, margen, margen)
            background = fondoRedondeado(Paleta.ACENTO_TENUE, dp(26f).toFloat(), Paleta.BORDE, dp(1f))
        }
        columna.addView(emblema, LinearLayout.LayoutParams(dp(52f), dp(52f)))

        columna.addView(
            TextView(this).estilo(Tipo.TITULO, Paleta.TEXTO, negrita = true, interlineado = 1f).apply {
                text = "Rama"
                gravity = Gravity.CENTER
                setPadding(0, dp(Espacio.M), 0, 0)
            }
        )
        columna.addView(
            TextView(this).estilo(Tipo.SECUNDARIO, Paleta.TEXTO_2, interlineado = 1.4f).apply {
                text = "Corro entera adentro de tu teléfono. El modelo que escribe mis " +
                    "respuestas es local: no consulto la IA de nadie y nada de lo que " +
                    "hablemos sale de acá."
                gravity = Gravity.CENTER
                setPadding(dp(Espacio.M), dp(Espacio.S), dp(Espacio.M), dp(Espacio.XL))
            }
        )

        columna.addView(
            TextView(this).apply { text = "Para empezar" }.rotulo().apply {
                gravity = Gravity.START
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(Espacio.S) }
            }
        )
        for (ejemplo in EJEMPLOS) columna.addView(filaDeEjemplo(ejemplo))
        return columna
    }

    /** Cada sugerencia es una fila entera y tocable, no un chip apretado. */
    private fun filaDeEjemplo(ejemplo: String): View {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = fondoPulsable(Paleta.SUPERFICIE, dp(Radio.MEDIO).toFloat(), Paleta.BORDE, dp(1f))
            setPadding(dp(Espacio.M + 2f), dp(Espacio.M), dp(Espacio.M), dp(Espacio.M))
            setOnClickListener { enviar(ejemplo) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(Espacio.S) }
        }
        fila.addView(
            TextView(this).estilo(Tipo.SECUNDARIO + 0.5f, Paleta.TEXTO, interlineado = 1f).apply {
                text = ejemplo
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        fila.addView(
            ImageView(this).apply {
                setImageDrawable(Icono(Iconos.chevron(), Paleta.TEXTO_3))
                rotation = -90f
            },
            LinearLayout.LayoutParams(dp(16f), dp(16f)),
        )
        return fila
    }

    // ----------------------------------------------------- barra de entrada

    /**
     * Todo lo de escribir dentro de una sola cápsula.
     *
     * Antes eran tres pastillas sueltas —adjuntar, campo, enviar— separadas por
     * aire, y la fila se leía como tres cosas distintas. Metidas en un mismo
     * contorno se leen como lo que son: un solo lugar donde uno escribe.
     */
    private fun construirBarraEntrada(): View {
        val barra = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(Espacio.M), 0, dp(Espacio.M), dp(Espacio.M))
        }

        val capsula = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            background = fondoRedondeado(Paleta.SUPERFICIE, dp(Radio.CAPSULA).toFloat(), Paleta.BORDE, dp(1f))
            setPadding(dp(Espacio.XS + 1f), dp(Espacio.XS + 1f), dp(Espacio.XS + 1f), dp(Espacio.XS + 1f))
        }

        capsula.addView(
            botonIcono(
                Iconos.mas(), "Adjuntar foto, PDF o video",
                lado = 38f, tamanioIcono = 18f,
                fondo = Color.TRANSPARENT, borde = Color.TRANSPARENT,
                color = Paleta.TEXTO_3,
            ) { pedirArchivo() },
            LinearLayout.LayoutParams(dp(38f), dp(38f)),
        )

        entrada = EditText(this).apply {
            hint = "Preguntame algo…"
            setHintTextColor(Paleta.TEXTO_3)
            setTextColor(Paleta.TEXTO)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Tipo.CUERPO)
            setLineSpacing(0f, 1.25f)
            background = null
            setPadding(dp(Espacio.XS), dp(Espacio.S + 2f), dp(Espacio.S), dp(Espacio.S + 2f))
            maxLines = 5
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
        capsula.addView(
            entrada,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )

        botonEnviar = botonIcono(
            Iconos.enviar(), "Enviar",
            lado = 38f, tamanioIcono = 18f,
            color = Paleta.SOBRE_ACENTO, fondo = Paleta.ACENTO, borde = Color.TRANSPARENT,
        ) {
            if (generando) {
                generando = false
                asistente?.cancelar()
                pintarBotonEnviar()
            } else {
                enviar(entrada.text.toString())
            }
        }
        capsula.addView(botonEnviar, LinearLayout.LayoutParams(dp(38f), dp(38f)))
        pintarBotonEnviar()

        barra.addView(capsula, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        return barra
    }

    /**
     * El botón de enviar dice en qué estado está la conversación.
     *
     * Apagado si no hay nada escrito, verde si hay algo para mandar, y un
     * cuadrado de detener mientras el modelo escribe.
     */
    private fun pintarBotonEnviar() {
        val hayTexto = entrada.text.isNotBlank()
        if (generando) {
            botonEnviar.setImageDrawable(Icono(Iconos.detener(), Paleta.TEXTO, relleno = true))
            botonEnviar.background = fondoPulsable(Paleta.SUPERFICIE_ALTA, dp(19f).toFloat(), Paleta.BORDE, dp(1f))
            botonEnviar.contentDescription = "Detener"
            botonEnviar.alpha = 1f
            return
        }
        botonEnviar.setImageDrawable(
            Icono(Iconos.enviar(), if (hayTexto) Paleta.SOBRE_ACENTO else Paleta.TEXTO_3)
        )
        botonEnviar.background = fondoPulsable(
            if (hayTexto) Paleta.ACENTO else Paleta.SUPERFICIE_ALTA,
            dp(19f).toFloat(),
            Paleta.BORDE,
            if (hayTexto) 0 else dp(1f),
        )
        botonEnviar.contentDescription = "Enviar"
        botonEnviar.alpha = 1f
    }

    // ----------------------------------------------------------- mensajes

    private fun enviar(texto: String) {
        val limpio = texto.trim()
        if (limpio.isEmpty()) return
        if (generando) {
            avisar("Esperá que termine de escribir, o tocá el botón de detener.")
            return
        }
        val ayudante = asistente
        if (ayudante == null) {
            avisar("Dame un segundo, todavía estoy cargando.")
            return
        }
        entrada.setText("")
        if (!asegurarModelo(limpio)) return
        quitarBienvenida()
        burbujaUsuario(limpio)

        val adjunto = ultimoAdjunto
        if (adjunto != null && PREGUNTA_POR_ADJUNTO.containsMatchIn(Texto.normalizar(limpio))) {
            responderSobreAdjunto(adjunto, limpio)
            return
        }

        val bloque = if (modoPensar) bloquePensar() else null
        val burbuja = burbujaRama("")
        mostrarPuntos()
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
                    quitarPuntos()
                    acumulado.append(fragmento)
                    burbuja.text = acumulado
                    alFinal()
                }
                generando
            }

            principal.post {
                quitarPuntos()
                generando = false
                pintarBotonEnviar()
                val texto = respuesta.texto
                burbuja.text = conFormato(texto)
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
            Respaldo.CALCULO, Respaldo.WEB -> Paleta.ACENTO
            Respaldo.BASE -> Paleta.TEXTO_3
            Respaldo.SOLO_MODELO -> Paleta.AVISO
        }
        val fondo = when (respaldo) {
            Respaldo.CALCULO, Respaldo.WEB -> Paleta.ACENTO_TENUE
            Respaldo.BASE -> Paleta.SUPERFICIE
            Respaldo.SOLO_MODELO -> Paleta.AVISO_TENUE
        }
        // Un aviso lleva su triángulo; lo demás, un visto. Dos iconos alcanzan:
        // lo que importa es si el dato tiene respaldo o no.
        val trazo = if (respaldo == Respaldo.SOLO_MODELO) Iconos.advertencia() else Iconos.visto()

        val sello = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            padding(dp(Espacio.S + 2f), dp(Espacio.XS + 1f))
            background = fondoPulsable(fondo, dp(Radio.CHICO).toFloat(), color, dp(1f))
            setOnClickListener { avisar(respaldo.explicacion) }
            contentDescription = respaldo.explicacion
        }
        sello.addView(
            ImageView(this).apply { setImageDrawable(Icono(trazo, color, grosor = 2.1f)) },
            LinearLayout.LayoutParams(dp(11f), dp(11f)).apply { rightMargin = dp(6f) },
        )
        sello.addView(TextView(this).apply { text = respaldo.etiqueta }.rotulo(color))

        val parametros = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.START
            topMargin = dp(Espacio.S + 2f)
            bottomMargin = dp(Espacio.XS)
        }
        contenedorChat.addView(sello, parametros)
        alFinal()
    }

    /** Las fuentes que consultó, como tarjeta aparte y tocable. */
    private fun fichaFuentes(fuentes: List<Resultado>) {
        val ficha = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = fondoRedondeado(Paleta.SUPERFICIE, dp(Radio.MEDIO).toFloat(), Paleta.BORDE, dp(1f))
            setPadding(dp(Espacio.L - 2f), dp(Espacio.M), dp(Espacio.L - 2f), dp(Espacio.M))
        }
        ficha.addView(
            TextView(this).apply { text = "Fuentes consultadas" }.rotulo().apply {
                setPadding(0, 0, 0, dp(Espacio.S))
            }
        )
        for ((i, fuente) in fuentes.withIndex()) {
            if (i > 0) ficha.addView(divisor().apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1f),
                ).apply { topMargin = dp(Espacio.S); bottomMargin = dp(Espacio.S) }
            })
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = fondoPulsable(Color.TRANSPARENT, dp(Radio.CHICO).toFloat())
                setOnClickListener { abrirEnlace(fuente.url) }
            }
            item.addView(
                TextView(this).estilo(Tipo.ETIQUETA + 1f, Paleta.TEXTO, interlineado = 1.25f).apply {
                    text = fuente.titulo
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
            )
            item.addView(
                TextView(this).estilo(Tipo.MICRO, Paleta.ACENTO, interlineado = 1f).apply {
                    text = fuente.url
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                    setPadding(0, dp(2f), 0, 0)
                }
            )
            ficha.addView(item)
        }
        val parametros = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(Espacio.S)
            bottomMargin = dp(Espacio.S)
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
        pintarEstadoModelo(cargando = true)
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
                    modeloEnPausa = false
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
        bienvenida = null
        indicador = null
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
        bienvenida = null
        indicador = null
        preferencias().edit().putString("chat", id).apply()

        for (mensaje in mensajes) {
            if (mensaje.rol == "user") burbujaUsuario(mensaje.contenido)
            else burbujaRama(mensaje.contenido)
        }
        actualizarSugerencias()
        alFinal()
    }

    /** La portada sólo tiene sentido con el chat en blanco. */
    private fun actualizarSugerencias() {
        if (historial.isEmpty()) mostrarBienvenida() else quitarBienvenida()
    }

    private fun saludar() = mostrarBienvenida()

    private fun mostrarBienvenida() {
        if (bienvenida != null) return
        val portada = construirBienvenida()
        bienvenida = portada
        contenedorChat.addView(portada, 0)
    }

    private fun quitarBienvenida() {
        bienvenida?.let { contenedorChat.removeView(it) }
        bienvenida = null
    }

    /**
     * Tres puntos latiendo mientras el modelo todavía no soltó una palabra.
     *
     * Es una vista aparte y no texto dentro de la respuesta: así el ancho no
     * salta a cada latido, y cuando llega el primer fragmento el indicador
     * desaparece sin dejar nada raro en el medio.
     */
    private fun mostrarPuntos() {
        quitarPuntos()
        val puntos = PuntosPensando(this)
        indicador = puntos
        contenedorChat.addView(
            puntos,
            LinearLayout.LayoutParams(dp(44f), dp(22f)).apply {
                topMargin = dp(Espacio.XS)
                bottomMargin = dp(Espacio.S)
            },
        )
        alFinal()
    }

    private fun quitarPuntos() {
        indicador?.let { contenedorChat.removeView(it) }
        indicador = null
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

    /**
     * Lo que escribe el usuario: una burbuja compacta, pegada a la derecha.
     *
     * Va en gris y no en el azul de antes. El color en un chat tiene que
     * significar algo, y acá lo único que necesita significado es el acento.
     */
    private fun burbujaUsuario(texto: String) {
        val burbuja = TextView(this).estilo(Tipo.CUERPO, Paleta.TEXTO, interlineado = 1.35f).apply {
            text = texto
            setPadding(dp(Espacio.L - 2f), dp(Espacio.M - 1f), dp(Espacio.L - 2f), dp(Espacio.M - 1f))
            setOnLongClickListener { copiar(texto); true }
            background = fondoRedondeado(
                Paleta.SUPERFICIE_ALTA, 0f, Paleta.BORDE, dp(1f),
                radios = esquinas(dp(Radio.BURBUJA).toFloat(), abajoDerecha = dp(Espacio.XS + 1f).toFloat()),
            )
        }
        val parametros = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.END
            topMargin = dp(Espacio.M)
            bottomMargin = dp(Espacio.XS)
        }
        burbuja.maxWidth = (resources.displayMetrics.widthPixels * 0.80f).toInt()
        contenedorChat.addView(burbuja, parametros)
        alFinal()
    }

    /**
     * Lo que escribe Rama: ancho completo y sin burbuja.
     *
     * Una respuesta de un modelo son párrafos, no una frase. Encerrarlos en un
     * globo del 84% del ancho los parte en renglones cortos y los hace difíciles
     * de leer, que es justo lo contrario de lo que se busca. Ancho completo,
     * con un rótulo arriba para saber quién habla, se lee como un texto.
     */
    private fun burbujaRama(texto: String): TextView {
        val bloque = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(Espacio.L)
                bottomMargin = dp(Espacio.XS)
            }
        }
        bloque.addView(rotuloDeRama())

        val cuerpo = TextView(this).estilo(Tipo.CUERPO, Paleta.TEXTO, interlineado = 1.5f).apply {
            text = conFormato(texto)
            setPadding(0, dp(Espacio.S - 2f), 0, 0)
            setOnLongClickListener { copiar(this.text.toString()); true }
        }
        bloque.addView(cuerpo)
        contenedorChat.addView(bloque)
        alFinal()
        return cuerpo
    }

    /** Un punto verde y el nombre: quién está hablando, sin ocupar lugar. */
    private fun rotuloDeRama(): View {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fila.addView(
            punto(Paleta.ACENTO, 6f),
            LinearLayout.LayoutParams(dp(6f), dp(6f)).apply { rightMargin = dp(7f) },
        )
        fila.addView(TextView(this).apply { text = "Rama" }.rotulo(Paleta.TEXTO_3))
        return fila
    }

    /**
     * Una nota del sistema, no de la conversación.
     *
     * Va centrada, en una pastilla tenue: se distingue de un turno del chat
     * sin necesidad de explicarlo.
     */
    private fun avisar(texto: String) {
        val nota = TextView(this).estilo(Tipo.MICRO + 0.5f, Paleta.TEXTO_3, interlineado = 1.25f).apply {
            text = texto
            gravity = Gravity.CENTER
            padding(dp(Espacio.M), dp(7f))
            background = fondoRedondeado(Paleta.SUPERFICIE, dp(Radio.PILDORA).toFloat())
            maxWidth = (resources.displayMetrics.widthPixels * 0.86f).toInt()
        }
        val parametros = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(Espacio.S)
            bottomMargin = dp(Espacio.S)
        }
        contenedorChat.addView(nota, parametros)
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
    private inner class BloquePensar(
        val tarjeta: LinearLayout,
        val cabecera: TextView,
        val flecha: ImageView,
        val detalle: LinearLayout,
    ) {

        fun agregarPaso(tituloPaso: String, detallePaso: String) {
            val titulo = TextView(this@MainActivity).apply { text = tituloPaso }.rotulo(Paleta.PENSAR)
            val cuerpo = TextView(this@MainActivity)
                .estilo(Tipo.ETIQUETA, Paleta.TEXTO_2, monoespaciada = true, interlineado = 1.35f).apply {
                    text = detallePaso
                    setPadding(0, dp(Espacio.XS), 0, dp(Espacio.M))
                }
            detalle.addView(titulo)
            detalle.addView(cuerpo)
        }

        fun cerrar(n: Int) {
            cabecera.text = "$n pasos de razonamiento"
            detalle.visibility = View.GONE
            flecha.rotation = 0f
            tarjeta.setOnClickListener {
                val visible = detalle.visibility == View.VISIBLE
                detalle.visibility = if (visible) View.GONE else View.VISIBLE
                flecha.rotation = if (visible) 0f else 180f
                if (!visible) alFinal()
            }
        }
    }

    /**
     * El razonamiento va en índigo, no en verde ni en violeta.
     *
     * Es información de segundo plano: tiene que distinguirse de la respuesta
     * sin competir con ella, y el acento verde ya está tomado por las acciones.
     */
    private fun bloquePensar(): BloquePensar {
        val tarjeta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = fondoPulsable(Paleta.PENSAR_TENUE, dp(Radio.MEDIO).toFloat(), Paleta.BORDE, dp(1f))
            setPadding(dp(Espacio.M + 2f), dp(Espacio.M), dp(Espacio.M + 2f), dp(Espacio.M))
        }

        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fila.addView(
            ImageView(this).apply {
                setImageDrawable(Icono(Iconos.destello(), Paleta.PENSAR, relleno = true))
            },
            LinearLayout.LayoutParams(dp(14f), dp(14f)).apply { rightMargin = dp(Espacio.S) },
        )
        val cabecera = TextView(this).apply { text = "Pensando" }.rotulo(Paleta.PENSAR)
        fila.addView(cabecera, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val flecha = ImageView(this).apply {
            setImageDrawable(Icono(Iconos.chevron(), Paleta.PENSAR))
        }
        fila.addView(flecha, LinearLayout.LayoutParams(dp(14f), dp(14f)))

        val detalle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(Espacio.M), 0, 0)
        }
        tarjeta.addView(fila)
        tarjeta.addView(detalle)

        val parametros = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(Espacio.L)
            bottomMargin = dp(Espacio.XS)
        }
        contenedorChat.addView(tarjeta, parametros)
        alFinal()
        return BloquePensar(tarjeta, cabecera, flecha, detalle)
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
        val cargando = TextView(this).estilo(Tipo.SECUNDARIO, Paleta.TEXTO_2, interlineado = 1f).apply {
            text = "Leyendo el archivo…"
            padding(dp(Espacio.L - 2f), dp(Espacio.M - 1f))
            background = fondoRedondeado(Paleta.SUPERFICIE, dp(Radio.MEDIO).toFloat(), Paleta.BORDE, dp(1f))
        }
        contenedorChat.addView(cargando, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.END
            topMargin = dp(Espacio.S)
            bottomMargin = dp(Espacio.XS)
        })
        alFinal()

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
            background = fondoRedondeado(
                Paleta.SUPERFICIE_ALTA, 0f, Paleta.BORDE, dp(1f),
                radios = esquinas(dp(Radio.BURBUJA).toFloat(), abajoDerecha = dp(Espacio.XS + 1f).toFloat()),
            )
            setPadding(dp(Espacio.XS + 1f), dp(Espacio.XS + 1f), dp(Espacio.XS + 1f), dp(Espacio.S + 2f))
        }

        val miniatura = adjunto.miniatura
        if (miniatura != null) {
            val imagen = ImageView(this).apply {
                setImageBitmap(miniatura)
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(vista: View, contorno: Outline) {
                        contorno.setRoundRect(0, 0, vista.width, vista.height, dp(14f).toFloat())
                    }
                }
            }
            val ancho = (resources.displayMetrics.widthPixels * 0.62f).toInt()
            val alto = (ancho * miniatura.height.toFloat() / miniatura.width).toInt()
                .coerceIn(dp(90f), dp(300f))
            tarjeta.addView(imagen, LinearLayout.LayoutParams(ancho, alto))
        }

        // El clip dibujado, y no un emoji distinto por cada tipo de archivo:
        // el tipo ya está escrito abajo, con todas las letras.
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(Espacio.S + 2f), dp(Espacio.S + 2f), dp(Espacio.S), dp(Espacio.XS) / 2)
        }
        fila.addView(
            ImageView(this).apply { setImageDrawable(Icono(Iconos.documento(), Paleta.TEXTO_2)) },
            LinearLayout.LayoutParams(dp(15f), dp(15f)).apply { rightMargin = dp(Espacio.S) },
        )
        fila.addView(
            TextView(this).estilo(Tipo.SECUNDARIO, Paleta.TEXTO, negrita = true, interlineado = 1.2f).apply {
                text = adjunto.nombre
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        tarjeta.addView(fila)
        tarjeta.addView(
            TextView(this).estilo(Tipo.MICRO, Paleta.TEXTO_3, interlineado = 1f).apply {
                text = "${adjunto.tipo} · ${AnalizadorAdjuntos.pesoLegible(adjunto.tamanioBytes)}"
                setPadding(dp(Espacio.XL + 3f), 0, dp(Espacio.S), 0)
            }
        )

        val parametros = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.END
            topMargin = dp(Espacio.M)
            bottomMargin = dp(Espacio.XS)
        }
        contenedorChat.addView(tarjeta, parametros)
        alFinal()
    }

    /** Tabla clave/valor con lo que se pudo extraer del archivo. */
    private fun fichaDatos(datos: List<Pair<String, String>>) {
        if (datos.isEmpty()) return
        val ficha = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = fondoRedondeado(Paleta.SUPERFICIE, dp(Radio.MEDIO).toFloat(), Paleta.BORDE, dp(1f))
            setPadding(dp(Espacio.L - 2f), dp(Espacio.M), dp(Espacio.L - 2f), dp(Espacio.M))
        }
        for ((clave, valor) in datos) {
            val fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val etiqueta = TextView(this).estilo(Tipo.ETIQUETA, Paleta.TEXTO_3, interlineado = 1.2f).apply {
                text = clave
            }
            val contenido = TextView(this).estilo(
                Tipo.ETIQUETA, Paleta.TEXTO, monoespaciada = true, interlineado = 1.2f,
            ).apply {
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

        /** Lo que se le ofrece a alguien que abre un chat en blanco. */
        private val EJEMPLOS = listOf(
            "¿Quién sos y cómo funcionás?",
            "Explicame algo difícil en palabras simples",
            "Ayudame a redactar un mensaje",
            "¿Qué podés hacer sin internet?",
        )
        private const val ARCHIVO_ERROR = "ultimo-error.txt"
        private const val ESPERA_ARRANQUE = 8000L
        private val PREGUNTA_POR_ADJUNTO =
            Regex("\\b(archivo|foto|imagen|pdf|video|adjunt\\w*|documento)\\b")
    }
}
