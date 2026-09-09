package ar.rama.ai

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * El sistema de diseño de Rama.
 *
 * La app no usa AndroidX ni Material: toda la apariencia se arma acá, con
 * drawables y trazos generados en código. Eso mantiene el APK en unos cientos
 * de KB y sin dependencias, pero obliga a tener la disciplina que una librería
 * te daría gratis: una sola paleta, una sola escala de espacios, una sola
 * escala tipográfica. Todo lo visual de la app sale de este archivo.
 */

// --------------------------------------------------------------------- color

/**
 * Un gris neutro apenas frío de fondo, un verde como única marca, y ámbar y
 * rojo reservados para cuando algo pide atención de verdad.
 *
 * La regla es que el acento se gana el lugar: pinta la acción principal y el
 * estado activo, y nada más. Cuando todo grita, no se entiende nada.
 */
object Paleta {
    /** El lienzo. Casi negro, con una pizca de azul para que no sea plano. */
    const val FONDO = 0xFF0A0C0F.toInt()

    /** Tarjetas, chips y la barra de escritura. */
    const val SUPERFICIE = 0xFF12151A.toInt()

    /** Lo que está por encima de una tarjeta: burbuja propia, botón secundario. */
    const val SUPERFICIE_ALTA = 0xFF1A1E25.toInt()

    /** El borde visible de una tarjeta. */
    const val BORDE = 0xFF232833.toInt()

    /** Separadores: se tienen que intuir, no ver. */
    const val BORDE_TENUE = 0xFF171B22.toInt()

    /** Texto de lectura. */
    const val TEXTO = 0xFFECEFF4.toInt()

    /** Texto de apoyo: descripciones, metadatos. */
    const val TEXTO_2 = 0xFF98A1B0.toInt()

    /** Texto al margen: sellos de tiempo, notas del sistema. */
    const val TEXTO_3 = 0xFF646D7C.toInt()

    /** La marca. Un verde de rama, no un verde de semáforo. */
    const val ACENTO = 0xFF3ECF8E.toInt()

    /** El mismo verde diluido, para fondos de estado activo. */
    const val ACENTO_TENUE = 0xFF102A20.toInt()

    /** Lo que se escribe encima del acento. */
    const val SOBRE_ACENTO = 0xFF04120B.toInt()

    /** Ámbar: falta algo, pero nada se rompió. */
    const val AVISO = 0xFFE0A458.toInt()
    const val AVISO_TENUE = 0xFF241B0F.toInt()

    /** Rojo: algo falló o se va a borrar. */
    const val ERROR = 0xFFE5786F.toInt()
    const val ERROR_TENUE = 0xFF2A1414.toInt()

    /** El razonamiento del modelo. Índigo: se lee técnico, no festivo. */
    const val PENSAR = 0xFF8B9CF7.toInt()
    const val PENSAR_TENUE = 0xFF151A2C.toInt()

    /** El agua del ripple sobre superficies oscuras. */
    const val TOQUE = 0x26FFFFFF
}

// -------------------------------------------------------------- medidas

/** La grilla de 4: todos los espacios de la app son múltiplos de esto. */
object Espacio {
    const val XS = 4f
    const val S = 8f
    const val M = 12f
    const val L = 16f
    const val XL = 24f
    const val XXL = 32f
}

/** Los cuatro radios que usa la app, y ninguno más. */
object Radio {
    const val CHICO = 8f
    const val MEDIO = 12f
    const val GRANDE = 16f
    const val BURBUJA = 18f
    const val CAPSULA = 24f
    const val PILDORA = 999f
}

/**
 * La escala tipográfica.
 *
 * Cinco tamaños alcanzan para toda la app. Tener menos opciones es lo que hace
 * que una pantalla se lea ordenada sin que uno sepa explicar por qué.
 */
object Tipo {
    const val TITULO = 20f
    const val SUBTITULO = 16f
    const val CUERPO = 15.5f
    const val SECUNDARIO = 13.5f
    const val ETIQUETA = 12f
    const val MICRO = 11f
}

fun Context.dp(valor: Float): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, valor, resources.displayMetrics).toInt()

// ------------------------------------------------------------- iconos

/**
 * Un icono dibujado a mano alzada sobre una grilla de 24×24.
 *
 * Antes la interfaz usaba emoji —☰, 🧠, 🗑— y eso es lo que más la delataba
 * como casera: cada teléfono los dibuja distinto, vienen en colores que uno no
 * eligió y no se pueden alinear con el texto. Un trazo propio se ve igual en
 * todos lados, toma el color que le demos y queda nítido en cualquier
 * densidad de pantalla.
 */
class Icono(
    private val ruta: Path,
    color: Int,
    grosor: Float = 1.9f,
    relleno: Boolean = false,
) : Drawable() {

    private val pincel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = if (relleno) Paint.Style.FILL else Paint.Style.STROKE
        strokeWidth = grosor
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun draw(canvas: Canvas) {
        val lado = minOf(bounds.width(), bounds.height()).toFloat()
        if (lado <= 0f) return
        canvas.save()
        canvas.translate(
            bounds.left + (bounds.width() - lado) / 2f,
            bounds.top + (bounds.height() - lado) / 2f,
        )
        // Escalar el lienzo escala también el grosor del trazo, así que el
        // icono conserva su proporción en cualquier tamaño.
        canvas.scale(lado / GRILLA, lado / GRILLA)
        canvas.drawPath(ruta, pincel)
        canvas.restore()
    }

    override fun setAlpha(alfa: Int) { pincel.alpha = alfa }
    override fun setColorFilter(filtro: ColorFilter?) { pincel.colorFilter = filtro }

    @Deprecated("Lo pide Drawable; en API 29 quedó obsoleto pero sigue siendo abstracto.")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        const val GRILLA = 24f
    }
}

/** Los trazos de cada icono, en coordenadas de la grilla de 24. */
object Iconos {

    private fun ruta(dibujar: Path.() -> Unit): Path = Path().apply(dibujar)

    private fun Path.linea(x1: Float, y1: Float, x2: Float, y2: Float) {
        moveTo(x1, y1); lineTo(x2, y2)
    }

    /** Historial de chats: tres renglones con su viñeta. */
    fun chats(): Path = ruta {
        for (y in listOf(6.5f, 12f, 17.5f)) {
            addCircle(5f, y, 1.4f, Path.Direction.CW)
            linea(9.5f, y, 19.5f, y)
        }
    }

    fun cerrar(): Path = ruta {
        linea(6.5f, 6.5f, 17.5f, 17.5f)
        linea(17.5f, 6.5f, 6.5f, 17.5f)
    }

    fun mas(): Path = ruta {
        linea(12f, 5.5f, 12f, 18.5f)
        linea(5.5f, 12f, 18.5f, 12f)
    }

    /** Enviar: una flecha hacia arriba, no un avioncito de papel. */
    fun enviar(): Path = ruta {
        linea(12f, 19f, 12f, 5.5f)
        moveTo(6f, 11.5f); lineTo(12f, 5.5f); lineTo(18f, 11.5f)
    }

    /** Detener: un cuadrado, el símbolo universal de cortar. */
    fun detener(): Path = ruta {
        addRoundRect(7.5f, 7.5f, 16.5f, 16.5f, 2f, 2f, Path.Direction.CW)
    }

    /** El destello del razonamiento: una estrella de cuatro puntas. */
    fun destello(): Path = ruta {
        moveTo(12f, 2.5f)
        cubicTo(12f, 7.5f, 16.5f, 12f, 21.5f, 12f)
        cubicTo(16.5f, 12f, 12f, 16.5f, 12f, 21.5f)
        cubicTo(12f, 16.5f, 7.5f, 12f, 2.5f, 12f)
        cubicTo(7.5f, 12f, 12f, 7.5f, 12f, 2.5f)
        close()
    }

    fun advertencia(): Path = ruta {
        moveTo(12f, 3.8f); lineTo(21.5f, 20f); lineTo(2.5f, 20f); close()
        linea(12f, 9.5f, 12f, 14f)
        addCircle(12f, 17f, 0.9f, Path.Direction.CW)
    }

    fun papelera(): Path = ruta {
        linea(4.5f, 6.5f, 19.5f, 6.5f)
        moveTo(9.5f, 6.5f); lineTo(9.5f, 4.5f); lineTo(14.5f, 4.5f); lineTo(14.5f, 6.5f)
        moveTo(6.5f, 6.5f); lineTo(7.5f, 19.5f); lineTo(16.5f, 19.5f); lineTo(17.5f, 6.5f)
    }

    fun descargar(): Path = ruta {
        linea(12f, 3.5f, 12f, 14.5f)
        moveTo(7f, 9.5f); lineTo(12f, 14.5f); lineTo(17f, 9.5f)
        moveTo(4.5f, 17.5f); lineTo(4.5f, 20f); lineTo(19.5f, 20f); lineTo(19.5f, 17.5f)
    }

    fun visto(): Path = ruta {
        moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 6.5f)
    }

    /**
     * Un archivo: la hoja con la esquina doblada.
     *
     * Acá había un clip, que es el icono habitual, pero un clip son dos curvas
     * paralelas que a 15 píxeles se pisan y quedan un borrón. Una hoja se lee
     * limpia en cualquier tamaño.
     */
    fun documento(): Path = ruta {
        moveTo(6f, 2.5f); lineTo(14f, 2.5f); lineTo(19f, 7.5f); lineTo(19f, 21.5f)
        lineTo(6f, 21.5f); close()
        moveTo(13.5f, 2.8f); lineTo(13.5f, 8f); lineTo(18.7f, 8f)
    }

    fun chevron(): Path = ruta {
        moveTo(7.5f, 10f); lineTo(12f, 14.5f); lineTo(16.5f, 10f)
    }

    /**
     * La marca: una rama con dos hojas.
     *
     * Es lo único figurativo de toda la app, y va sólo en el encabezado y en
     * la pantalla de bienvenida.
     */
    fun marca(): Path = ruta {
        moveTo(12f, 21.5f); lineTo(12f, 9f)
        moveTo(12f, 15.5f)
        cubicTo(8.4f, 15.5f, 5.5f, 12.6f, 5.5f, 9f)
        cubicTo(9.1f, 9f, 12f, 11.9f, 12f, 15.5f)
        moveTo(12f, 12f)
        cubicTo(12f, 8.1f, 15.1f, 5f, 19f, 5f)
        cubicTo(19f, 8.9f, 15.9f, 12f, 12f, 12f)
    }
}

// -------------------------------------------------------------- fondos

/** Rectángulo redondeado, opcionalmente con borde. */
fun fondoRedondeado(
    color: Int,
    radio: Float,
    borde: Int = Color.TRANSPARENT,
    anchoBorde: Int = 0,
    radios: FloatArray? = null,
): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    setColor(color)
    if (radios != null) cornerRadii = radios else cornerRadius = radio
    if (anchoBorde > 0) setStroke(anchoBorde, borde)
}

/** El mismo fondo pero con la onda del toque recortada a su forma. */
fun fondoPulsable(
    color: Int,
    radio: Float,
    borde: Int = Color.TRANSPARENT,
    anchoBorde: Int = 0,
    radios: FloatArray? = null,
): Drawable {
    val forma = fondoRedondeado(color, radio, borde, anchoBorde, radios)
    return RippleDrawable(
        ColorStateList.valueOf(Paleta.TOQUE),
        forma,
        // La misma forma de máscara: sin esto la onda se sale por las esquinas.
        fondoRedondeado(Color.WHITE, radio, radios = radios),
    )
}

/**
 * Una tarjeta con una franja de color a la izquierda.
 *
 * Es la manera de marcar el tono de un aviso sin pintar el bloque entero: una
 * barra ámbar de tres píxeles dice lo mismo que un fondo naranja y no le grita
 * al usuario cada vez que abre la app.
 */
fun fondoConFranja(color: Int, franja: Int, radio: Float, anchoFranja: Int): Drawable {
    val capas = LayerDrawable(
        arrayOf(
            fondoRedondeado(franja, radio),
            fondoRedondeado(color, radio),
        )
    )
    capas.setLayerInset(1, anchoFranja, 0, 0, 0)
    return capas
}

// ------------------------------------------------------------ texto

/** Aplica de una los ajustes que repetimos en cada texto de la interfaz. */
fun TextView.estilo(
    tamanio: Float,
    color: Int = Paleta.TEXTO,
    negrita: Boolean = false,
    monoespaciada: Boolean = false,
    interlineado: Float = 1.3f,
): TextView = apply {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, tamanio)
    setTextColor(color)
    if (monoespaciada) typeface = Typeface.MONOSPACE
    if (negrita) setTypeface(typeface, Typeface.BOLD)
    setLineSpacing(0f, interlineado)
}

/**
 * El renglón chiquito en versales que encabeza una sección.
 *
 * Separa sin necesidad de una línea ni de un título grande.
 */
fun TextView.rotulo(color: Int = Paleta.TEXTO_3): TextView = apply {
    estilo(Tipo.MICRO, color, negrita = true, interlineado = 1f)
    letterSpacing = 0.11f
    text = text.toString().uppercase()
}

fun View.padding(horizontal: Int, vertical: Int) =
    setPadding(horizontal, vertical, horizontal, vertical)

// ------------------------------------------------------- piezas armadas

/** Un botón cuadrado con un icono adentro, del tamaño mínimo que se puede tocar. */
fun Context.botonIcono(
    trazo: Path,
    descripcion: String,
    lado: Float = 40f,
    tamanioIcono: Float = 19f,
    color: Int = Paleta.TEXTO_2,
    fondo: Int = Paleta.SUPERFICIE,
    borde: Int = Paleta.BORDE,
    relleno: Boolean = false,
    alTocar: () -> Unit,
): ImageView = ImageView(this).apply {
    setImageDrawable(Icono(trazo, color, relleno = relleno))
    val margen = dp((lado - tamanioIcono) / 2f)
    setPadding(margen, margen, margen, margen)
    background = fondoPulsable(fondo, dp(lado / 2f).toFloat(), borde, if (borde == Color.TRANSPARENT) 0 else dp(1f))
    contentDescription = descripcion
    setOnClickListener { alTocar() }
    layoutParams = LinearLayout.LayoutParams(dp(lado), dp(lado))
}

/** Un botón de texto. El acento se reserva para la acción principal. */
fun Context.boton(
    texto: String,
    principal: Boolean = false,
    color: Int = if (principal) Paleta.SOBRE_ACENTO else Paleta.TEXTO,
    fondo: Int = if (principal) Paleta.ACENTO else Paleta.SUPERFICIE_ALTA,
    alTocar: () -> Unit,
): TextView = TextView(this).estilo(Tipo.SECUNDARIO + 0.5f, color, negrita = true, interlineado = 1f).apply {
    this.text = texto
    gravity = Gravity.CENTER
    padding(dp(Espacio.L), dp(Espacio.M - 1f))
    background = fondoPulsable(
        fondo,
        dp(Radio.MEDIO).toFloat(),
        Paleta.BORDE,
        if (principal) 0 else dp(1f),
    )
    setOnClickListener { alTocar() }
}

/** Un punto de color: dice el estado de algo en el menor espacio posible. */
fun Context.punto(color: Int, diametro: Float = 7f): View = View(this).apply {
    background = fondoRedondeado(color, dp(diametro / 2f).toFloat())
    layoutParams = LinearLayout.LayoutParams(dp(diametro), dp(diametro))
}

/** Una línea de separación que se intuye pero no se mira. */
fun Context.divisor(color: Int = Paleta.BORDE_TENUE): View = View(this).apply {
    setBackgroundColor(color)
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1f))
}

fun Context.espacio(alto: Float): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(alto))
}

/**
 * Los tres puntos que laten mientras el modelo arranca.
 *
 * Antes eran caracteres «·» escritos en la burbuja, que saltaban de ancho a
 * cada latido. Dibujados, respiran en su lugar.
 */
class PuntosPensando(contexto: Context) : View(contexto) {

    private val pincel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Paleta.TEXTO_3 }
    private val radio = contexto.dp(3.5f).toFloat()
    private val separacion = contexto.dp(11f).toFloat()
    private var fase = 0f

    private val latido = object : Runnable {
        override fun run() {
            fase += 0.12f
            invalidate()
            postDelayed(this, 60)
        }
    }

    init {
        layoutParams = LinearLayout.LayoutParams(
            contexto.dp(44f), contexto.dp(22f),
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(latido)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(latido)
    }

    override fun onDraw(lienzo: Canvas) {
        val centroY = height / 2f
        val inicioX = radio + separacion / 3f
        for (i in 0..2) {
            // Cada punto va un tercio de ciclo atrás del anterior: la onda se
            // lee como si algo estuviera pensando, no como tres luces sueltas.
            val onda = Math.sin((fase - i * 0.6f).toDouble()).toFloat()
            pincel.alpha = (110 + 110 * onda).toInt().coerceIn(60, 255)
            lienzo.drawCircle(inicioX + i * separacion, centroY, radio, pincel)
        }
    }
}

// ------------------------------------------------------------- markdown

/**
 * Convierte el markdown liviano de los modelos en texto con formato.
 *
 * Sin esto el chat muestra los asteriscos y las almohadillas tal cual, que es
 * exactamente lo que uno no quiere leer.
 */
fun conFormato(markdown: String): CharSequence {
    val analizado = ar.rama.ai.motor.Formato.analizar(markdown)
    if (analizado.marcas.isEmpty()) return analizado.texto

    val texto = android.text.SpannableStringBuilder(analizado.texto)
    for (marca in analizado.marcas) {
        if (marca.desde >= marca.hasta || marca.hasta > texto.length) continue
        val estilo: Any = when (marca.enfasis) {
            ar.rama.ai.motor.Enfasis.NEGRITA ->
                android.text.style.StyleSpan(Typeface.BOLD)
            ar.rama.ai.motor.Enfasis.CURSIVA ->
                android.text.style.StyleSpan(Typeface.ITALIC)
            ar.rama.ai.motor.Enfasis.CODIGO ->
                android.text.style.TypefaceSpan("monospace")
            ar.rama.ai.motor.Enfasis.TITULO ->
                android.text.style.StyleSpan(Typeface.BOLD)
        }
        texto.setSpan(estilo, marca.desde, marca.hasta, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (marca.enfasis == ar.rama.ai.motor.Enfasis.CODIGO) {
            texto.setSpan(
                android.text.style.ForegroundColorSpan(Paleta.ACENTO),
                marca.desde, marca.hasta, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            texto.setSpan(
                android.text.style.RelativeSizeSpan(0.94f),
                marca.desde, marca.hasta, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        if (marca.enfasis == ar.rama.ai.motor.Enfasis.TITULO) {
            texto.setSpan(
                android.text.style.RelativeSizeSpan(1.1f),
                marca.desde, marca.hasta, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }
    return texto
}
