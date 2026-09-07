package ar.rama.ai.motor

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

class ErrorCalculo(mensaje: String) : Exception(mensaje)

/**
 * Evaluador aritmético por descenso recursivo.
 *
 * Se escribe a mano en lugar de usar un intérprete de propósito general: así
 * la calculadora sólo puede hacer cuentas, nunca ejecutar otra cosa.
 *
 * expresion := termino (('+' | '-') termino)*
 * termino   := unario (('*' | '/' | '%') unario)*
 * unario    := ('-' | '+')? potencia
 * potencia  := primario ('^' unario)?          // asociativa a derecha
 * primario  := numero | '(' expresion ')'
 */
object Calculadora {

    const val LIMITE_EXPONENTE = 1000.0

    fun evaluar(expresion: String): Double {
        val analizador = Analizador(expresion.replace("**", "^"))
        val valor = analizador.expresion()
        analizador.exigirFin()
        if (valor.isNaN() || valor.isInfinite()) throw ErrorCalculo("resultado fuera de rango")
        return valor
    }

    fun formatear(valor: Double): String {
        if (abs(valor - valor.roundToLong()) < 1e-9 && abs(valor) < 1e15) {
            return valor.roundToLong().toString()
        }
        val redondeado = Math.round(valor * 1e6) / 1e6
        return redondeado.toString()
    }

    private class Analizador(private val texto: String) {
        private var i = 0

        fun exigirFin() {
            saltarEspacios()
            if (i < texto.length) throw ErrorCalculo("no entiendo «${texto.substring(i)}»")
        }

        fun expresion(): Double {
            var valor = termino()
            while (true) {
                saltarEspacios()
                when (mirar()) {
                    '+' -> { i++; valor += termino() }
                    '-' -> { i++; valor -= termino() }
                    else -> return valor
                }
            }
        }

        private fun termino(): Double {
            var valor = unario()
            while (true) {
                saltarEspacios()
                val operador = mirar()
                if (operador != '*' && operador != '/' && operador != '%') return valor
                i++
                val derecha = unario()
                if ((operador == '/' || operador == '%') && derecha == 0.0) {
                    throw ErrorCalculo("división por cero")
                }
                valor = when (operador) {
                    '*' -> valor * derecha
                    '/' -> valor / derecha
                    else -> valor % derecha
                }
            }
        }

        private fun unario(): Double {
            saltarEspacios()
            return when (mirar()) {
                '-' -> { i++; -unario() }
                '+' -> { i++; unario() }
                else -> potencia()
            }
        }

        private fun potencia(): Double {
            val base = primario()
            saltarEspacios()
            if (mirar() != '^') return base
            i++
            val exponente = unario()
            if (abs(exponente) > LIMITE_EXPONENTE) throw ErrorCalculo("exponente demasiado grande")
            return base.pow(exponente)
        }

        private fun primario(): Double {
            saltarEspacios()
            val c = mirar() ?: throw ErrorCalculo("falta un número")
            if (c == '(') {
                i++
                val valor = expresion()
                saltarEspacios()
                if (mirar() != ')') throw ErrorCalculo("falta cerrar el paréntesis")
                i++
                return valor
            }
            if (!c.isDigit() && c != '.') throw ErrorCalculo("«$c» no es un número")

            val inicio = i
            while (i < texto.length && (texto[i].isDigit() || texto[i] == '.')) i++
            return texto.substring(inicio, i).toDoubleOrNull()
                ?: throw ErrorCalculo("número mal escrito")
        }

        private fun mirar(): Char? = if (i < texto.length) texto[i] else null

        private fun saltarEspacios() {
            while (i < texto.length && texto[i].isWhitespace()) i++
        }
    }
}
