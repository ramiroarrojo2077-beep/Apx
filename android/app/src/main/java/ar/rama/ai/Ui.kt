package ar.rama.ai

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.View
import android.widget.TextView

/**
 * Paleta y helpers de interfaz.
 *
 * La app no usa AndroidX ni Material: toda la apariencia se arma acá con
 * drawables generados en código. Así el APK pesa unos cientos de KB y no
 * depende de ninguna librería externa.
 */
object Paleta {
    const val FONDO = 0xFF0F1115.toInt()
    const val PANEL = 0xFF171A21.toInt()
    const val PANEL_ALTO = 0xFF1E2430.toInt()
    const val BORDE = 0xFF262B35.toInt()
    const val TEXTO = 0xFFE6E9EF.toInt()
    const val TENUE = 0xFF8B93A4.toInt()
    const val ACENTO = 0xFF4ADE80.toInt()
    const val ACENTO_OSCURO = 0xFF08130C.toInt()
    const val USUARIO = 0xFF1F6FEB.toInt()
    const val PENSAR = 0xFFB794F6.toInt()
}

fun Context.dp(valor: Float): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, valor, resources.displayMetrics).toInt()

fun Context.sp(valor: Float): Float = valor

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

/** El mismo fondo pero con feedback táctil al tocarlo. */
fun fondoPulsable(color: Int, radio: Float, borde: Int = Color.TRANSPARENT, anchoBorde: Int = 0) =
    RippleDrawable(
        ColorStateList.valueOf(0x33FFFFFF),
        fondoRedondeado(color, radio, borde, anchoBorde),
        null,
    )

/** Aplica de una los ajustes que repetimos en cada texto de la interfaz. */
fun TextView.estilo(
    tamanio: Float,
    color: Int = Paleta.TEXTO,
    negrita: Boolean = false,
    monoespaciada: Boolean = false,
): TextView = apply {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, tamanio)
    setTextColor(color)
    if (negrita) setTypeface(typeface, android.graphics.Typeface.BOLD)
    if (monoespaciada) typeface = android.graphics.Typeface.MONOSPACE
    setLineSpacing(0f, 1.25f)
}

fun View.padding(horizontal: Int, vertical: Int) = setPadding(horizontal, vertical, horizontal, vertical)
