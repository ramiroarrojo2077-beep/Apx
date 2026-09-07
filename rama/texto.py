"""Procesamiento de texto en español: normalización, tokenización y stemming."""

import re
import unicodedata

# Palabras vacías: aportan poco significado y sólo ensucian la similitud.
STOPWORDS = {
    "a", "al", "algo", "algun", "alguna", "algunas", "alguno", "algunos", "ante",
    "aqui", "asi", "aunque", "cada", "como", "con", "contra", "cual", "cuales",
    "cuando", "de", "del", "desde", "donde", "dos", "el", "ella", "ellas", "ello",
    "ellos", "en", "entre", "era", "eran", "eres", "es", "esa", "esas", "ese",
    "eso", "esos", "esta", "estan", "estas", "este", "esto", "estos", "estoy",
    "fue", "fueron", "ha", "han", "has", "hasta", "hay", "he", "la", "las", "le",
    "les", "lo", "los", "mas", "me", "mi", "mis", "mucho", "muy", "nada", "ni",
    "no", "nos", "nosotros", "o", "os", "otra", "otro", "para", "pero", "poco",
    "por", "porque", "que", "quien", "se", "segun", "ser", "si", "sin", "sobre",
    "solo", "son", "soy", "su", "sus", "tambien", "te", "tener", "tengo", "ti",
    "tiene", "tienen", "todo", "todos", "tu", "tus", "un", "una", "uno", "unos",
    "vos", "y", "ya", "yo",
}

# Sufijos ordenados de más largo a más corto: el primero que encaje gana.
_SUFIJOS = (
    "amientos", "imientos", "amiento", "imiento", "aciones", "adoras", "adores",
    "ancias", "logias", "ucions", "encias", "amente", "aciona", "adora", "ador",
    "ancia", "logia", "ucion", "encia", "mente", "anza", "icos", "icas", "ismo",
    "able", "ible", "ista", "osos", "osas", "ico", "ica", "oso", "osa", "iva",
    "ivo", "ando", "iendo", "ados", "idos", "ada", "ido", "ar", "er", "ir", "es",
    "as", "os", "s",
)

_LIMPIEZA = re.compile(r"[^a-z0-9ñ\s]+")
_ESPACIOS = re.compile(r"\s+")


def quitar_acentos(texto: str) -> str:
    """Elimina tildes y diéresis pero conserva la ñ."""
    texto = texto.replace("ñ", "\0").replace("Ñ", "\0")
    descompuesto = unicodedata.normalize("NFD", texto)
    plano = "".join(c for c in descompuesto if unicodedata.category(c) != "Mn")
    return plano.replace("\0", "ñ")


def normalizar(texto: str) -> str:
    """Pasa a minúsculas, quita acentos, signos y espacios sobrantes."""
    texto = quitar_acentos(texto.lower())
    texto = _LIMPIEZA.sub(" ", texto)
    return _ESPACIOS.sub(" ", texto).strip()


def raiz(palabra: str) -> str:
    """Stemmer ligero: recorta sufijos frecuentes del español.

    No pretende ser lingüísticamente exacto, sólo hacer que "programar",
    "programando" y "programas" caigan en el mismo cubo.
    """
    if len(palabra) <= 4:
        return palabra
    for sufijo in _SUFIJOS:
        if palabra.endswith(sufijo) and len(palabra) - len(sufijo) >= 3:
            return palabra[: -len(sufijo)]
    return palabra


def tokenizar(texto: str, con_raiz: bool = True, quitar_vacias: bool = True) -> list[str]:
    """Convierte texto libre en la lista de tokens que usa el motor."""
    palabras = normalizar(texto).split()
    if quitar_vacias:
        filtradas = [p for p in palabras if p not in STOPWORDS]
        # Si la frase era toda stopwords ("¿cómo estás?"), no la vaciamos.
        palabras = filtradas or palabras
    return [raiz(p) for p in palabras] if con_raiz else palabras
