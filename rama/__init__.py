"""Rama AI - una mini inteligencia artificial conversacional en español.

Sin dependencias externas: todo el motor (normalización, TF-IDF, similitud
coseno, skills y aprendizaje) está construido sobre la librería estándar.
"""

from .cerebro import Rama, Respuesta
from .memoria import Memoria

__version__ = "1.0.0"
__all__ = ["Rama", "Respuesta", "Memoria", "__version__"]
