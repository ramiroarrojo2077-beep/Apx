"""Servidor HTTP mínimo para chatear con Rama desde el navegador.

Sólo `http.server` de la stdlib. Escucha en localhost por defecto: Rama no
está pensada para quedar expuesta en internet.
"""

import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Lock

from . import __version__
from .cerebro import Rama
from .memoria import Memoria

RUTA_WEB = Path(__file__).resolve().parent.parent / "web" / "index.html"
LIMITE_CUERPO = 16 * 1024  # 16 KB alcanza y sobra para un mensaje de chat.


class ManejadorRama(BaseHTTPRequestHandler):
    """Rutas: `/` (interfaz), `/api/chat` (POST) y `/api/estado` (GET)."""

    server_version = f"RamaAI/{__version__}"
    ia: Rama
    candado: Lock

    def log_message(self, formato: str, *args) -> None:  # pragma: no cover
        print(f"  {self.address_string()} · {formato % args}")

    # ------------------------------------------------------------- salida

    def _responder(self, codigo: int, cuerpo: bytes, tipo: str) -> None:
        self.send_response(codigo)
        self.send_header("Content-Type", tipo)
        self.send_header("Content-Length", str(len(cuerpo)))
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(cuerpo)

    def _json(self, codigo: int, datos: dict) -> None:
        self._responder(codigo, json.dumps(datos, ensure_ascii=False).encode("utf-8"),
                        "application/json; charset=utf-8")

    # --------------------------------------------------------------- rutas

    def do_GET(self) -> None:  # noqa: N802 (nombre impuesto por la stdlib)
        ruta = self.path.split("?", 1)[0]
        if ruta in ("/", "/index.html"):
            try:
                html = RUTA_WEB.read_bytes()
            except OSError:
                self._json(500, {"error": "no encuentro web/index.html"})
                return
            self._responder(200, html, "text/html; charset=utf-8")
        elif ruta == "/api/estado":
            self._json(200, {
                "version": __version__,
                "intenciones": self.ia.total_intenciones,
                "patrones": len(self.ia.entradas),
                "aprendido": len(self.ia.memoria.aprendido),
            })
        else:
            self._json(404, {"error": "ruta desconocida"})

    def do_POST(self) -> None:  # noqa: N802
        if self.path.split("?", 1)[0] != "/api/chat":
            self._json(404, {"error": "ruta desconocida"})
            return

        try:
            largo = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            largo = 0
        if largo <= 0 or largo > LIMITE_CUERPO:
            self._json(400, {"error": "cuerpo vacío o demasiado grande"})
            return

        try:
            peticion = json.loads(self.rfile.read(largo).decode("utf-8"))
            mensaje = str(peticion["mensaje"])
        except (json.JSONDecodeError, UnicodeDecodeError, KeyError, TypeError):
            self._json(400, {"error": "esperaba JSON con la clave 'mensaje'"})
            return

        # El cerebro tiene estado (memoria, índice): un turno por vez.
        with self.candado:
            respuesta = self.ia.responder(mensaje)

        self._json(200, {
            "respuesta": respuesta.texto,
            "intencion": respuesta.intencion,
            "confianza": respuesta.confianza,
            "fuente": respuesta.fuente,
            "candidatos": respuesta.candidatos,
        })


def crear_servidor(host: str, puerto: int, ia: Rama) -> ThreadingHTTPServer:
    manejador = type("ManejadorConIA", (ManejadorRama,), {"ia": ia, "candado": Lock()})
    return ThreadingHTTPServer((host, puerto), manejador)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="rama.servidor", description="Chat web de Rama AI")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--puerto", type=int, default=8000)
    parser.add_argument("--conocimiento")
    parser.add_argument("--memoria")
    args = parser.parse_args(argv)

    ia = Rama(conocimiento=args.conocimiento,
              memoria=Memoria(archivo=args.memoria) if args.memoria else None)
    servidor = crear_servidor(args.host, args.puerto, ia)
    print(f"Rama AI escuchando en http://{args.host}:{args.puerto}  (Ctrl+C para cortar)")
    try:
        servidor.serve_forever()
    except KeyboardInterrupt:
        print("\nCerrando.")
    finally:
        servidor.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
