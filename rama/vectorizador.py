"""Vectorizador TF-IDF con similitud coseno, escrito a mano sobre stdlib.

Cada documento se representa como un vector disperso (dict) que mezcla dos
familias de rasgos:

* raíces de palabra  -> capturan el significado ("program", "python")
* trigramas de letra -> toleran erratas ("pyhton" sigue pareciéndose a "python")
"""

import math
from collections import Counter
from collections.abc import Iterable

from .correccion import Corrector
from .texto import normalizar, tokenizar

PESO_PALABRA = 1.0
PESO_NGRAMA = 0.5
N_GRAMA = 3


def _ngramas(texto: str, n: int = N_GRAMA) -> list[str]:
    nucleo = normalizar(texto)
    if not nucleo:
        return []
    plano = f" {nucleo} "
    if len(plano) <= n:
        return [plano]
    return [plano[i : i + n] for i in range(len(plano) - n + 1)]


def rasgos(texto: str) -> Counter:
    """Cuenta los rasgos crudos (sin IDF ni pesos) de un texto."""
    bolsa: Counter = Counter()
    palabras = tokenizar(texto)
    for palabra in palabras:
        bolsa[f"p:{palabra}"] += 1
    # Bigramas de palabra: distinguen "no puedo" de "puedo".
    for a, b in zip(palabras, palabras[1:]):
        bolsa[f"b:{a}_{b}"] += 1
    for gram in _ngramas(texto):
        bolsa[f"n:{gram}"] += 1
    return bolsa


def _peso_familia(rasgo: str) -> float:
    """Los n-gramas de letra pesan menos: son la red de seguridad, no la señal."""
    return PESO_NGRAMA if rasgo.startswith("n:") else PESO_PALABRA


class Vectorizador:
    """TF-IDF entrenado sobre un corpus fijo, con transformación incremental."""

    def __init__(self) -> None:
        self.idf: dict[str, float] = {}
        self.idf_defecto: float = 1.0
        self.n_documentos: int = 0
        self.corrector = Corrector()

    def entrenar(self, documentos: Iterable[str]) -> "Vectorizador":
        documentos = list(documentos)
        self.n_documentos = len(documentos)
        self.corrector.entrenar(documentos)
        frecuencia_doc: Counter = Counter()
        for doc in documentos:
            for rasgo in set(rasgos(doc)):
                frecuencia_doc[rasgo] += 1

        total = max(self.n_documentos, 1)
        self.idf = {
            rasgo: math.log((total + 1) / (df + 1)) + 1.0
            for rasgo, df in frecuencia_doc.items()
        }
        # Un rasgo nunca visto es, por definición, muy informativo.
        self.idf_defecto = math.log(total + 1) + 1.0
        return self

    def vectorizar(self, texto: str, corregir: bool = False) -> dict[str, float]:
        """Devuelve el vector TF-IDF normalizado (norma L2 = 1).

        Con `corregir=True` las erratas se acercan al vocabulario conocido;
        se usa sólo para la consulta, nunca para indexar el corpus.
        """
        if corregir:
            texto = self.corrector.corregir(texto)
        bolsa = rasgos(texto)
        if not bolsa:
            return {}
        vector = {
            rasgo: (1.0 + math.log(tf))
            * self.idf.get(rasgo, self.idf_defecto)
            * _peso_familia(rasgo)
            for rasgo, tf in bolsa.items()
        }
        norma = math.sqrt(sum(v * v for v in vector.values()))
        if norma == 0:
            return {}
        return {rasgo: valor / norma for rasgo, valor in vector.items()}


def similitud(a: dict[str, float], b: dict[str, float]) -> float:
    """Coseno entre dos vectores ya normalizados: producto punto directo."""
    if not a or not b:
        return 0.0
    if len(a) > len(b):
        a, b = b, a
    return sum(peso * b.get(rasgo, 0.0) for rasgo, peso in a.items())
