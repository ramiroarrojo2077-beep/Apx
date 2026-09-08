"""Habilidades deterministas de Rama.

Cada skill es una función `(texto, ctx) -> str | None`. Devolver `None`
significa "esto no es para mí, que siga el motor de intenciones".
El contexto `ctx` expone la memoria y el propio cerebro.
"""

import ast
import operator
import random
import re
from datetime import date, datetime

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
    if re.search(r"\bque (dia|fecha) es hoy\b|\bfecha de hoy\b|\bque dia estamos\b|"
                 r"^que (dia|fecha) es$|\bque dia es hoy\b", plano):
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


# ------------------------------------------------------------- geografía

_CAPITAL_DE = re.compile(r"(?:cual es la |cual es |decime la |dame la )?capital (?:de|del) (?:la |el |los )?(.+)")
_ES_CAPITAL = re.compile(r"(?:de que pais es (?:la )?capital|de donde es capital) (.+)")
_PAIS_DE_CAPITAL = re.compile(r"(.+?) es (?:la )?capital de que pais")


def _buscar_pais(nombre: str, ctx):
    """Resuelve el nombre de un país tolerando alias y ruido alrededor."""
    clave = normalizar(nombre).strip(" ?.!")
    clave = re.sub(r"^(pais |republica de |la |el )", "", clave).strip()
    clave = ctx.alias.get(clave, clave)
    if clave in ctx.capitales:
        return ctx.capitales[clave]
    # "capital de francia hoy" y variantes con palabras de más.
    for candidato, datos in ctx.capitales.items():
        if candidato in clave.split(" ") or clave.startswith(candidato + " "):
            return datos
    return None


def skill_geografia(texto: str, ctx) -> str | None:
    plano = normalizar(texto)

    m = _CAPITAL_DE.search(plano)
    if m:
        encontrado = _buscar_pais(m.group(1), ctx)
        if encontrado:
            pais, capital = encontrado
            return f"La capital de {pais} es {capital}."
        return None

    m = _ES_CAPITAL.search(plano) or _PAIS_DE_CAPITAL.search(plano)
    if m:
        buscada = normalizar(m.group(1)).strip(" ?.!")
        for pais, capital in ctx.capitales.values():
            if normalizar(capital).startswith(buscada) or buscada in normalizar(capital).split(" "):
                return f"{capital} es la capital de {pais}."
    return None


# ---------------------------------------------------- conversión de unidades

# Cada unidad se define por su equivalencia en la unidad base de su familia.
_UNIDADES = {
    "longitud": {
        "base": "metro",
        "unidades": {
            "km": 1000.0, "kilometro": 1000.0, "kilometros": 1000.0,
            "m": 1.0, "metro": 1.0, "metros": 1.0,
            "cm": 0.01, "centimetro": 0.01, "centimetros": 0.01,
            "mm": 0.001, "milimetro": 0.001, "milimetros": 0.001,
            "milla": 1609.344, "millas": 1609.344, "mi": 1609.344,
            "pie": 0.3048, "pies": 0.3048, "ft": 0.3048,
            "pulgada": 0.0254, "pulgadas": 0.0254, "in": 0.0254,
            "yarda": 0.9144, "yardas": 0.9144,
        },
    },
    "masa": {
        "base": "kilogramo",
        "unidades": {
            "kg": 1.0, "kilo": 1.0, "kilos": 1.0, "kilogramo": 1.0, "kilogramos": 1.0,
            "g": 0.001, "gramo": 0.001, "gramos": 0.001,
            "mg": 0.000001, "miligramo": 0.000001, "miligramos": 0.000001,
            "tonelada": 1000.0, "toneladas": 1000.0,
            "libra": 0.45359237, "libras": 0.45359237, "lb": 0.45359237,
            "onza": 0.028349523, "onzas": 0.028349523, "oz": 0.028349523,
        },
    },
    "volumen": {
        "base": "litro",
        "unidades": {
            "l": 1.0, "litro": 1.0, "litros": 1.0,
            "ml": 0.001, "mililitro": 0.001, "mililitros": 0.001,
            "galon": 3.785411784, "galones": 3.785411784,
            "taza": 0.24, "tazas": 0.24,
        },
    },
    "velocidad": {
        "base": "km/h",
        "unidades": {
            "kmh": 1.0, "km h": 1.0, "kilometros por hora": 1.0,
            "mph": 1.609344, "millas por hora": 1.609344,
            "nudo": 1.852, "nudos": 1.852,
            "ms": 3.6, "metros por segundo": 3.6,
        },
    },
}

_TEMPERATURAS = {"celsius", "centigrados", "c", "fahrenheit", "f", "kelvin", "k"}
_CONVERSION = re.compile(
    r"(?:cuant[oa]s?\s+([a-z/ ]+?)\s+(?:son|es|hay en|equivale[n]?\s*a)\s*)?"
    r"(\d+(?:[.,]\d+)?)\s*(?:grados\s+)?([a-z/º°]+)\s*(?:a|en|son|equivalen a)\s+(?:grados\s+)?([a-z/ ]+)"
)
_CUANTOS_SON = re.compile(
    r"cuant[oa]s?\s+(?:grados\s+)?([a-z/ ]+?)\s+(?:son|es|equivale[n]?\s*a)\s+(\d+(?:[.,]\d+)?)\s*(?:grados\s+)?([a-z/º°]+)"
)


def _familia(unidad: str):
    for nombre, familia in _UNIDADES.items():
        if unidad in familia["unidades"]:
            return nombre, familia
    return None, None


def _limpiar_unidad(texto: str) -> str:
    return normalizar(texto).replace("º", "").replace("°", "").strip()


def _convertir_temperatura(valor: float, desde: str, hacia: str):
    celsius = {
        "celsius": valor, "centigrados": valor, "c": valor,
        "fahrenheit": (valor - 32) * 5 / 9, "f": (valor - 32) * 5 / 9,
        "kelvin": valor - 273.15, "k": valor - 273.15,
    }.get(desde)
    if celsius is None:
        return None
    return {
        "celsius": celsius, "centigrados": celsius, "c": celsius,
        "fahrenheit": celsius * 9 / 5 + 32, "f": celsius * 9 / 5 + 32,
        "kelvin": celsius + 273.15, "k": celsius + 273.15,
    }.get(hacia)


def skill_conversiones(texto: str, ctx) -> str | None:
    plano = normalizar(texto).replace(",", ".")

    m = _CUANTOS_SON.search(plano)
    if m:
        hacia, valor, desde = _limpiar_unidad(m.group(1)), float(m.group(2)), _limpiar_unidad(m.group(3))
    else:
        m = _CONVERSION.search(plano)
        if not m:
            return None
        valor, desde, hacia = float(m.group(2)), _limpiar_unidad(m.group(3)), _limpiar_unidad(m.group(4))

    if desde in _TEMPERATURAS and hacia in _TEMPERATURAS:
        resultado = _convertir_temperatura(valor, desde, hacia)
        if resultado is None:
            return None
        return f"{formatear_numero(valor)}° {desde} son {formatear_numero(round(resultado, 2))}° {hacia}."

    familia_desde, datos = _familia(desde)
    familia_hacia, _ = _familia(hacia)
    if not familia_desde or familia_desde != familia_hacia:
        return None
    resultado = valor * datos["unidades"][desde] / datos["unidades"][hacia]
    return f"{formatear_numero(valor)} {desde} son {formatear_numero(round(resultado, 6))} {hacia}."


# ------------------------------------------------------ fechas y calendario

_MESES_NUM = {m: n for n, m in enumerate(MESES, start=1)}
_FECHA_TEXTO = re.compile(r"(\d{1,2})\s+de\s+([a-z]+)(?:\s+de(?:l)?\s+(\d{4}))?")
_FECHA_BARRAS = re.compile(r"(\d{1,2})[/-](\d{1,2})(?:[/-](\d{2,4}))?")
_QUE_DIA_CAE = re.compile(r"que dia (?:cae|es|fue|sera)\b")
_CUANTO_FALTA = re.compile(r"cuantos? (?:dias|falta|faltan)\b|cuanto falta\b")
_EDAD = re.compile(r"(?:cuantos años tengo|que edad tengo|mi edad)")


def _interpretar_fecha(plano: str, anio_defecto: int):
    m = _FECHA_TEXTO.search(plano)
    if m:
        mes = _MESES_NUM.get(quitar_acentos(m.group(2)))
        if not mes:
            mes = next((n for nombre, n in _MESES_NUM.items()
                        if quitar_acentos(nombre).startswith(m.group(2)[:4])), None)
        if mes:
            anio = int(m.group(3)) if m.group(3) else anio_defecto
            try:
                return date(anio, mes, int(m.group(1)))
            except ValueError:
                return None
    m = _FECHA_BARRAS.search(plano)
    if m:
        anio = m.group(3)
        anio = int(anio) if anio and len(anio) == 4 else (2000 + int(anio) if anio else anio_defecto)
        try:
            return date(anio, int(m.group(2)), int(m.group(1)))
        except ValueError:
            return None
    return None


def skill_calendario(texto: str, ctx) -> str | None:
    plano = normalizar(texto)
    hoy = date.today()

    if _EDAD.search(plano):
        nacimiento = _interpretar_fecha(plano, hoy.year)
        anio = re.search(r"\b(19\d{2}|20\d{2})\b", plano)
        if nacimiento:
            edad = hoy.year - nacimiento.year - ((hoy.month, hoy.day) < (nacimiento.month, nacimiento.day))
            return f"Tenés {edad} años."
        if anio:
            n = int(anio.group(1))
            return f"Si naciste en {n}, este año cumplís {hoy.year - n}."
        return None

    if _CUANTO_FALTA.search(plano):
        objetivo = _interpretar_fecha(plano, hoy.year)
        if objetivo is None and "navidad" in plano:
            objetivo = date(hoy.year, 12, 25)
        if objetivo is None and ("año nuevo" in plano or "fin de año" in plano):
            objetivo = date(hoy.year, 12, 31)
        if objetivo is None:
            return None
        if objetivo < hoy:
            objetivo = date(objetivo.year + 1, objetivo.month, objetivo.day)
        faltan = (objetivo - hoy).days
        cuando = f"{objetivo.day} de {MESES[objetivo.month - 1]} de {objetivo.year}"
        if faltan == 0:
            return f"Es hoy mismo: {cuando}."
        return f"Faltan {faltan} días para el {cuando} ({DIAS[objetivo.weekday()]})."

    if _QUE_DIA_CAE.search(plano):
        objetivo = _interpretar_fecha(plano, hoy.year)
        if objetivo is None:
            return None
        return (f"El {objetivo.day} de {MESES[objetivo.month - 1]} de {objetivo.year} "
                f"cae {DIAS[objetivo.weekday()]}.")
    return None


# ----------------------------------------------------- operaciones de texto

_CUANTAS_LETRAS = re.compile(r"cuantas letras tiene (?:la palabra )?[«\"']?(.+?)[»\"']?$", re.IGNORECASE)
_CUANTAS_PALABRAS = re.compile(r"cuantas palabras tiene (?:la frase )?[«\"']?(.+?)[»\"']?$", re.IGNORECASE)
_AL_REVES = re.compile(r"(?:escribi|deci|poné|pone|dame)?\s*[«\"']?(.+?)[»\"']?\s+al reves", re.IGNORECASE)
_PALINDROMO = re.compile(r"[«\"']?(.+?)[»\"']?\s+es (?:un )?palindromo|es palindromo [«\"']?(.+?)[»\"']?$", re.IGNORECASE)


def skill_texto(texto: str, ctx) -> str | None:
    if m := _CUANTAS_LETRAS.search(texto):
        palabra = m.group(1).strip(" ?.!¿¡")
        letras = sum(1 for c in palabra if c.isalpha())
        return f"«{palabra}» tiene {letras} letras."

    if m := _CUANTAS_PALABRAS.search(texto):
        frase = m.group(1).strip(" ?.!¿¡")
        return f"Esa frase tiene {len(frase.split())} palabras."

    if m := _AL_REVES.search(texto):
        palabra = m.group(1).strip(" ?.!¿¡")
        if palabra:
            return f"«{palabra}» al revés es «{palabra[::-1]}»."

    if m := _PALINDROMO.search(texto):
        palabra = (m.group(1) or m.group(2) or "").strip(" ?.!¿¡")
        limpio = normalizar(palabra).replace(" ", "")
        if limpio:
            es = limpio == limpio[::-1]
            return (f"Sí, «{palabra}» es un palíndromo: se lee igual en los dos sentidos."
                    if es else f"No, «{palabra}» no es un palíndromo.")
    return None


# Los comandos de control van antes que todo, incluso antes de lo aprendido:
# si no, «olvidá x» podría ser respondido por el propio «x» que aprendimos.
COMANDOS = (skill_aprendizaje,)

# El orden importa: lo más específico primero.
SKILLS = (
    skill_nombre,
    skill_fecha_hora,
    skill_calendario,
    skill_geografia,
    skill_conversiones,
    skill_texto,
    skill_azar,
    skill_memoria_conversacion,
    skill_calculadora,
)
