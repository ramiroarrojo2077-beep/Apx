"""Habilidades deterministas de Rama.

Cada skill es una función `(texto, ctx) -> str | None`. Devolver `None`
significa "esto no es para mí, que siga el motor de intenciones".
El contexto `ctx` expone la memoria y el propio cerebro.
"""

import ast
import operator
import random
import re
from datetime import datetime

from .texto import normalizar, quitar_acentos

DIAS = ["lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo"]
MESES = [
    "enero", "febrero", "marzo", "abril", "mayo", "junio", "julio",
    "agosto", "septiembre", "octubre", "noviembre", "diciembre",
]

_OPERADORES = {
    ast.Add: operator.add,
    ast.Sub: operator.sub,
    ast.Mult: operator.mul,
    ast.Div: operator.truediv,
    ast.FloorDiv: operator.floordiv,
    ast.Mod: operator.mod,
    ast.Pow: operator.pow,
    ast.USub: operator.neg,
    ast.UAdd: operator.pos,
}

LIMITE_EXPONENTE = 1_000


class ErrorCalculo(Exception):
    """La expresión no es una operación aritmética evaluable."""


def evaluar_expresion(expresion: str) -> float:
    """Evalúa aritmética con un AST restringido (nunca `eval` a secas)."""

    def _eval(nodo: ast.AST) -> float:
        if isinstance(nodo, ast.Expression):
            return _eval(nodo.body)
        if isinstance(nodo, ast.Constant) and isinstance(nodo.value, (int, float)):
            return nodo.value
        if isinstance(nodo, ast.UnaryOp) and type(nodo.op) in _OPERADORES:
            return _OPERADORES[type(nodo.op)](_eval(nodo.operand))
        if isinstance(nodo, ast.BinOp) and type(nodo.op) in _OPERADORES:
            izq, der = _eval(nodo.left), _eval(nodo.right)
            if isinstance(nodo.op, ast.Pow) and abs(der) > LIMITE_EXPONENTE:
                raise ErrorCalculo("exponente demasiado grande")
            if isinstance(nodo.op, (ast.Div, ast.FloorDiv, ast.Mod)) and der == 0:
                raise ErrorCalculo("división por cero")
            return _OPERADORES[type(nodo.op)](izq, der)
        raise ErrorCalculo("expresión no permitida")

    try:
        arbol = ast.parse(expresion, mode="eval")
    except SyntaxError as exc:
        raise ErrorCalculo("sintaxis inválida") from exc
    return _eval(arbol)


def formatear_numero(valor: float) -> str:
    if isinstance(valor, float) and valor.is_integer():
        return str(int(valor))
    return f"{round(valor, 6)}"


# --------------------------------------------------------------- skills

_EXPRESION = re.compile(r"^[\s\d+\-*/%^().,]+$")
_PALABRAS_OPERADOR = (
    (r"\b(?:mas|sumado a)\b", "+"),
    (r"\b(?:menos|restado)\b", "-"),
    (r"\b(?:por|multiplicado por)\b", "*"),
    (r"\b(?:dividido(?: por| entre)?|entre)\b", "/"),
    (r"\b(?:elevado a(?: la)?)\b", "**"),
)


def _traducir_operadores(texto: str) -> str:
    for patron, simbolo in _PALABRAS_OPERADOR:
        texto = re.sub(patron, simbolo, texto)
    return texto
_PISTA_CALCULO = re.compile(
    r"\b(cuanto es|cuanto da|calcula|calcular|calculame|resultado de|suma|resta|"
    r"multiplica|divide)\b"
)
_PORCENTAJE = re.compile(r"(\d+(?:[.,]\d+)?)\s*(?:%|por ?ciento)\s*de\s*(\d+(?:[.,]\d+)?)")
_RAIZ = re.compile(r"raiz(?: cuadrada)?(?: de)?\s*(\d+(?:[.,]\d+)?)")


def skill_calculadora(texto: str, ctx) -> str | None:
    plano = normalizar(texto)
    # El % y el punto decimal no sobreviven a normalizar(), así que para las
    # expresiones trabajamos sobre el texto crudo sin tildes.
    literal = quitar_acentos(texto.lower()).replace(",", ".")
    crudo = texto.strip().replace("×", "*").replace("÷", "/").replace("^", "**")

    if m := _PORCENTAJE.search(literal):
        pct, total = float(m.group(1)), float(m.group(2))
        return f"El {formatear_numero(pct)}% de {formatear_numero(total)} es {formatear_numero(total * pct / 100)}."

    if m := _RAIZ.search(literal):
        numero = float(m.group(1))
        return f"La raíz cuadrada de {formatear_numero(numero)} es {formatear_numero(numero ** 0.5)}."

    # "2 mas 2" es aritmética escrita en castellano.
    if re.search(r"\d", plano) and any(re.search(p, plano) for p, _ in _PALABRAS_OPERADOR):
        traducido = _traducir_operadores(re.sub(r"^\s*(?:cuanto (?:es|da)|calcula\w*)\s*", "", plano))
        if re.fullmatch(r"[\s\d+\-*/().]+", traducido) and re.search(r"[-+*/]", traducido):
            try:
                return f"{traducido.strip()} = {formatear_numero(evaluar_expresion(traducido))}"
            except ErrorCalculo:
                pass

    candidato = crudo
    if _PISTA_CALCULO.search(plano):
        # "cuánto es 12*7?" -> nos quedamos sólo con la parte aritmética.
        numeros = re.findall(r"[\d+\-*/%().\s]*\d[\d+\-*/%().\s]*", crudo)
        candidato = max(numeros, key=len).strip() if numeros else ""
    elif not _EXPRESION.match(crudo) or not any(c.isdigit() for c in crudo):
        return None

    candidato = candidato.rstrip("?¿!¡. ").replace("^", "**")
    if not candidato or not re.search(r"[-+*/%]", candidato):
        return None
    try:
        resultado = evaluar_expresion(candidato)
    except ErrorCalculo as exc:
        if "cero" in str(exc):
            return "No se puede dividir por cero. Es la única regla que la matemática no negocia."
        return None
    return f"{candidato.strip()} = {formatear_numero(resultado)}"


def skill_fecha_hora(texto: str, ctx) -> str | None:
    plano = normalizar(texto)
    ahora = datetime.now()
    if re.search(r"\bque hora es\b|\bhora actual\b|\bdame la hora\b", plano):
        return f"Son las {ahora.strftime('%H:%M')}."
    if re.search(r"\bque (dia|fecha) es\b|\bfecha de hoy\b|\bque dia estamos\b", plano):
        dia = DIAS[ahora.weekday()]
        return f"Hoy es {dia} {ahora.day} de {MESES[ahora.month - 1]} de {ahora.year}."
    return None


_ME_LLAMO = re.compile(
    r"\b(?:me llamo|mi nombre es|soy)\s+([a-zñáéíóúü]+(?:\s+[a-zñáéíóúü]+)?)\b",
    re.IGNORECASE,
)
_COMO_ME_LLAMO = re.compile(r"\b(como me llamo|cual es mi nombre|sabes mi nombre)\b")
_NO_NOMBRES = {"yo", "un", "una", "el", "la", "de", "que", "muy", "tu", "vos"}


def skill_nombre(texto: str, ctx) -> str | None:
    plano = normalizar(texto)
    if _COMO_ME_LLAMO.search(plano):
        nombre = ctx.memoria.recuerdo("nombre")
        if nombre:
            return f"Te llamás {nombre}. No me olvido de esas cosas."
        return "Todavía no me dijiste tu nombre. Probá con «me llamo ...»."

    if m := _ME_LLAMO.search(texto):
        candidato = m.group(1).strip()
        primera = normalizar(candidato).split()[0] if normalizar(candidato) else ""
        if not primera or primera in _NO_NOMBRES or len(primera) < 2:
            return None
        nombre = " ".join(p.capitalize() for p in candidato.split())
        ctx.memoria.recordar("nombre", nombre)
        return f"Un gusto, {nombre}. Ya lo anoté en mi memoria."
    return None


_MONEDA = re.compile(r"\b(tira|tirar|lanza|lanzar|arroja)\b.*\bmoneda\b|\bcara o (ceca|cruz)\b")
_DADO = re.compile(r"\bdado\b")
_CARAS = re.compile(r"\bdado (?:de )?(\d+)|\bde (\d+) caras\b")
_ELEGIR = re.compile(r"\b(elegi|elige|escoge|eleg[ií]|decidi|decide)\b(?: entre)?\s+(.+)")


def skill_azar(texto: str, ctx) -> str | None:
    plano = normalizar(texto)
    if _MONEDA.search(plano):
        return f"Salió {random.choice(['cara', 'ceca'])}."
    if _DADO.search(plano) and re.search(r"\b(tira|tirar|lanza|lanzar|dame)\b", plano):
        m = _CARAS.search(plano)
        caras = int(next(g for g in m.groups() if g)) if m else 6
        caras = max(2, min(caras, 1000))
        return f"Tiré un dado de {caras} caras y salió {random.randint(1, caras)}."
    if m := _ELEGIR.search(plano):
        opciones = [o.strip() for o in re.split(r"\bo\b|,|/", m.group(2)) if o.strip()]
        if len(opciones) >= 2:
            return f"Yo iría por: {random.choice(opciones)}."
    return None


_APRENDE = re.compile(
    r"^\s*(?:aprende|aprend[eé]|record[aá]|ense[nñ]ate)\s*[:,]?\s*(.+?)\s*(?:=|\||->|=>)\s*(.+)$",
    re.IGNORECASE,
)
_OLVIDA = re.compile(r"^\s*olvid[aá](?:te de)?\s*[:,]?\s*(.+)$", re.IGNORECASE)


def skill_aprendizaje(texto: str, ctx) -> str | None:
    if m := _APRENDE.match(texto):
        pregunta, respuesta = m.group(1), m.group(2)
        ctx.memoria.aprender(pregunta, respuesta)
        ctx.reindexar()
        return f"Listo, aprendí que «{pregunta.strip()}» → «{respuesta.strip()}»."

    if m := _OLVIDA.match(texto):
        objetivo = m.group(1).strip()
        if ctx.memoria.olvidar(objetivo):
            ctx.reindexar()
            return f"Borrado. Ya no recuerdo nada sobre «{objetivo}»."
        return f"No tenía nada guardado sobre «{objetivo}»."

    # "responde: ..." corrige la última pregunta: sirve tanto después de un
    # "no sé" como para enmendar una respuesta que no te convenció.
    pendiente = ctx.memoria.ultima_pregunta_sin_respuesta
    if pendiente is None:
        preguntas = [t for quien, t in ctx.memoria.historial if quien == "usuario"]
        pendiente = preguntas[-2] if len(preguntas) >= 2 else None
    if pendiente:
        m = re.match(r"^\s*(?:se dice|responde|contesta|la respuesta es)\s*[:,]?\s*(.+)$",
                     texto, re.IGNORECASE)
        if m:
            ctx.memoria.aprender(pendiente, m.group(1))
            ctx.memoria.ultima_pregunta_sin_respuesta = None
            ctx.reindexar()
            return f"Gracias, lo guardé. La próxima que me preguntes «{pendiente}» voy a saber."
    return None


def skill_memoria_conversacion(texto: str, ctx) -> str | None:
    plano = normalizar(texto)
    if re.search(r"\bque (te dije|dije) (antes|recien)\b|\bque hablamos\b", plano):
        mios = [t for quien, t in ctx.memoria.historial if quien == "usuario"]
        if len(mios) >= 2:
            return f"Lo último que me dijiste antes de esto fue: «{mios[-2]}»."
        return "Todavía no hablamos lo suficiente como para tener pasado."
    if re.search(r"\bcuanto (sabes|aprendiste)\b|\bque aprendiste\b", plano):
        n = len(ctx.memoria.aprendido)
        base = ctx.total_intenciones
        if n == 0:
            return f"Manejo {base} temas de base y todavía no me enseñaste nada nuevo."
        ejemplos = ", ".join(f"«{h['pregunta']}»" for h in ctx.memoria.aprendido[-3:])
        return f"Manejo {base} temas de base y {n} cosas que me enseñaste vos, por ejemplo: {ejemplos}."
    return None


# El orden importa: lo más específico primero.
SKILLS = (
    skill_aprendizaje,
    skill_nombre,
    skill_fecha_hora,
    skill_azar,
    skill_memoria_conversacion,
    skill_calculadora,
)
