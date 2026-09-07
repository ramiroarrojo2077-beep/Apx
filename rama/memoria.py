"""Memoria persistente de Rama: lo que aprende y lo que recuerda de vos."""

import json
import os
import tempfile
from collections import deque
from pathlib import Path
from typing import Any

RUTA_DATOS = Path(__file__).resolve().parent.parent / "data"
ARCHIVO_APRENDIDO = RUTA_DATOS / "aprendido.json"
MAX_HISTORIAL = 40


class Memoria:
    """Guarda hechos aprendidos y el estado de la conversación en curso.

    Lo aprendido se persiste en disco; el historial vive sólo en la sesión.
    """

    def __init__(self, archivo: Path | str | None = None) -> None:
        self.archivo = Path(archivo) if archivo else ARCHIVO_APRENDIDO
        self.aprendido: list[dict[str, Any]] = []
        self.perfil: dict[str, Any] = {}
        self.historial: deque[tuple[str, str]] = deque(maxlen=MAX_HISTORIAL)
        self.ultima_pregunta_sin_respuesta: str | None = None
        self.cargar()

    # ---------------------------------------------------------------- disco

    def cargar(self) -> None:
        if not self.archivo.exists():
            return
        try:
            datos = json.loads(self.archivo.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError):
            # Un archivo corrupto no debe impedir que Rama arranque.
            return
        self.aprendido = datos.get("aprendido", [])
        self.perfil = datos.get("perfil", {})

    def guardar(self) -> None:
        self.archivo.parent.mkdir(parents=True, exist_ok=True)
        datos = {"aprendido": self.aprendido, "perfil": self.perfil}
        # Escritura atómica: nunca dejamos el archivo a medio escribir.
        fd, temporal = tempfile.mkstemp(dir=self.archivo.parent, suffix=".tmp")
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                json.dump(datos, f, ensure_ascii=False, indent=2)
            os.replace(temporal, self.archivo)
        except BaseException:
            Path(temporal).unlink(missing_ok=True)
            raise

    # ------------------------------------------------------------ contenido

    def aprender(self, pregunta: str, respuesta: str) -> None:
        """Registra un par pregunta/respuesta, actualizando si ya existía."""
        pregunta, respuesta = pregunta.strip(), respuesta.strip()
        for hecho in self.aprendido:
            if hecho["pregunta"].lower() == pregunta.lower():
                hecho["respuesta"] = respuesta
                self.guardar()
                return
        self.aprendido.append({"pregunta": pregunta, "respuesta": respuesta})
        self.guardar()

    def olvidar(self, pregunta: str) -> bool:
        antes = len(self.aprendido)
        objetivo = pregunta.strip().lower()
        self.aprendido = [
            h for h in self.aprendido if h["pregunta"].lower() != objetivo
        ]
        if len(self.aprendido) != antes:
            self.guardar()
            return True
        return False

    def recordar(self, clave: str, valor: Any) -> None:
        self.perfil[clave] = valor
        self.guardar()

    def recuerdo(self, clave: str, defecto: Any = None) -> Any:
        return self.perfil.get(clave, defecto)

    def registrar_turno(self, quien: str, texto: str) -> None:
        self.historial.append((quien, texto))
