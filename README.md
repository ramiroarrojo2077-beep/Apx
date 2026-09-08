# Rama AI

Una IA que corre **entera adentro de tu teléfono**. Escribe sus propias respuestas
con un modelo de lenguaje local — no es un caparazón de ChatGPT ni de ninguna otra
IA: no hay API, ni cuenta, ni clave, ni servidor. Los pesos están en tu disco y la
generación ocurre en tu procesador.

Busca en la web cuando la pregunta lo necesita, y tiene habilidades deterministas
que le ponen los números exactos para que no los invente.

## Cómo está armada

```
tu pregunta
     │
     ├─ 1. comandos          aprende: / olvidá / responde:      → se ejecutan y listo
     ├─ 2. lo que enseñaste  tu corrección le gana a todo lo demás
     ├─ 3. habilidades       cuentas, capitales, fechas, conversiones  → DATO EXACTO
     ├─ 4. base local        TF-IDF sobre 94 temas               → CONTEXTO
     ├─ 5. búsqueda web      DuckDuckGo, sin API key             → FUENTES
     │
     └─ 6. el modelo redacta la respuesta con todo eso por delante
```

El modelo escribe; los otros cinco evitan que invente. Un modelo de 0.6B multiplica
mal y confunde fechas: por eso las cuentas las hace la calculadora y las capitales
salen de una tabla, no de la red neuronal.

Si no hay modelo cargado, Rama sigue funcionando con los pasos 1 a 4: respuestas
acotadas pero exactas, sin internet.

## El modelo

No viene dentro del APK: son cientos de megas y no todos quieren el mismo
compromiso. Se elige y se descarga desde la app (botón del modelo, arriba a la
derecha), una sola vez.

| Modelo | Peso | RAM | Cómo va |
|---|---|---|---|
| **Qwen3 0.6B** | ~400 MB | 3 GB | Rápido en cualquier teléfono. Escribe bien, se equivoca seguido en datos. |
| **Qwen3 1.7B** | ~1.1 GB | 6 GB | Más coherente y con más conocimiento propio. Lento y calienta en teléfonos modestos. |

También podés **importar cualquier .gguf** que ya tengas: la app lo copia y lo usa.

La inferencia es [llama.cpp](https://github.com/ggml-org/llama.cpp) (fijado en la
versión `b10855`) compilado dentro del APK para `arm64-v8a`, con un puente JNI
propio en `app/src/main/cpp/rama_llama.cpp`.

## Modos

La fila de arriba cambia cómo escribe. No son disfraces: cada modo mueve la
temperatura del muestreo, cuánto se extiende y si sale a buscar.

| Modo | Para qué | Temperatura | Largo | Busca |
|---|---|---|---|---|
| 💬 **Charla** | conversación, dos o tres oraciones | 0.7 | 320 | sí |
| 🎯 **Preciso** | ir al dato; se calla si no sabe | 0.2 | 280 | sí |
| 📚 **Explicar** | entender algo, con ejemplo y paso a paso | 0.5 | 700 | sí |
| ✨ **Creativo** | escribir, imaginar, jugar | 1.0 | 700 | no |
| ⚡ **Al hueso** | una o dos frases, nada más | 0.4 | 120 | sí |

**Siempre en español.** La regla de idioma va primera y en mayúsculas en el
mensaje de sistema de todos los modos: los modelos chicos multilingües se van
al inglés apenas pueden, y eso es lo que más lo frena.

**Sin pensamientos en el texto.** Los modelos tipo Qwen3 escriben su
razonamiento entre `<think>` y `</think>` antes de contestar. Rama lo saca del
chat de dos formas: le pide al modelo que no razone en voz alta (`/no_think`,
que Qwen3 entiende) y además **filtra la salida mientras llega**, por si igual
lo escribe. Ese razonamiento no se tira: aparece en el modo pensar 🧠, que es
donde tiene sentido leerlo.

El filtro trabaja sobre texto que llega de a pedacitos, así que aguanta que la
etiqueta venga partida entre dos tokens (`<th` + `ink>`), que el modelo nunca
la cierre, y que aparezca un `<` suelto en una fórmula.

## Chats guardados

Cada conversación se guarda sola apenas escribís, sin botón de guardar. El
botón **☰** de arriba a la izquierda abre la lista: título tomado de tu
primera pregunta, cuándo fue, cuántos mensajes. Tocás uno y se retoma donde
quedó; **＋ Chat nuevo** empieza de cero.

Todo vive en el almacenamiento privado de la app, un archivo JSON por chat.
No sale del teléfono, no se sincroniza con nada, y desinstalar la app se lo
lleva todo. Borrar un chat o borrarlos todos pide dos toques, para que no
pase por accidente.

Detalles de la interfaz: mantené apretado cualquier mensaje para copiarlo,
las sugerencias desaparecen cuando la conversación arranca, y mientras el
modelo piensa laten tres puntitos en vez de quedarse en blanco.

## Qué sale del teléfono, y qué no

- **Tus conversaciones: nunca.** El modelo corre local. No hay a dónde mandarlas.
- **Internet se usa para dos cosas:** descargar los pesos del modelo, una vez; y
  buscar en la web cuando la pregunta depende de datos actuales o vos lo pedís.
- **Permisos:** sólo `INTERNET`. Los archivos llegan por el selector del sistema,
  así que Rama ve únicamente lo que le pasás.

## Uso

Requiere Python 3.10+ y nada más.

La versión de escritorio (`rama/`, en Python) mantiene el motor de recuperación
sin la parte generativa: sirve para trabajar sobre la base de conocimiento.

```bash
python3 -m rama                      # chat interactivo en la terminal
python3 -m rama "qué es python"      # una pregunta suelta
python3 -m rama.servidor             # chat web en http://127.0.0.1:8000
python3 -m rama.evaluar --detalle    # mide la precisión del clasificador
python3 -m unittest discover -s tests -q   # tests
```

### Comandos de la terminal

| Comando | Qué hace |
|---|---|
| `/ayuda` | lista de comandos |
| `/debug` | muestra fuente, intención, confianza y candidatos de cada respuesta |
| `/aprendido` | todo lo que le enseñaste |
| `/olvidar X` | borra lo aprendido sobre X |
| `/stats` | tamaño de la base de conocimiento |
| `/salir` | terminar |

### Qué entiende

Su base tiene **94 temas** y **337 formas de preguntarlos**, más habilidades que
*calculan* la respuesta en vez de recitarla:

- **Charla**: saludos, despedidas, estados de ánimo, aburrimiento, nervios, consejos
  de estudio, sueño, dinero, ejercicio, entrevistas de trabajo, recomendaciones.
- **Tecnología**: internet, wifi, bluetooth, GPS, algoritmos, bases de datos, APIs,
  servidores, virus, contraseñas, cifrado, blockchain, machine learning, redes
  neuronales, Git, Linux, Android, RAM, procesadores, batería.
- **Ciencia**: por qué el cielo es azul, cómo llueve, gravedad, fotosíntesis, ADN,
  átomos, edad del universo y de la Tierra, dinosaurios, velocidad de la luz, fases
  de la Luna.
- **Geografía**: la capital de cualquiera de **188 países**, y a la inversa
  (`¿de qué país es capital Roma?`).
- **Cuentas**: `12*7`, `(3+5)*2`, `2 mas 2`, `el 15% de 200`, `raíz de 144`.
- **Conversiones**: `cuántas millas son 100 km`, `5 kg en libras`,
  `20 grados celsius a fahrenheit`. Longitud, masa, volumen, velocidad y temperatura.
- **Calendario**: `¿qué día cae el 25 de diciembre?`, `¿cuántos días faltan para
  navidad?`, `¿cuántos años tengo si nací en 1990?`.
- **Texto**: `¿cuántas letras tiene murciélago?`, `python al revés`,
  `neuquen es palíndromo`.
- **Fecha y hora**: `¿qué hora es?`, `¿qué día es hoy?`.
- **Azar**: `tirá una moneda`, `tirá un dado de 20`, `elegí entre pizza o empanadas`.
- **Memoria**: `me llamo Ramiro` → después `¿cómo me llamo?`.
- **Aprendizaje**: `aprende: pregunta = respuesta`, o `responde: ...` para corregir
  la última respuesta. Se guarda en `data/aprendido.json` y sobrevive al reinicio.

### El orden de prioridad

Cuando le preguntás algo, Rama resuelve en este orden, y no es arbitrario:

1. **Comandos** (`aprende:`, `olvidá`, `responde:`) — siempre primero, o «olvidá x»
   sería respondido por el propio «x» que aprendiste.
2. **Lo que le enseñaste vos** — si le enseñaste que la capital de Francia es otra
   cosa, eso le gana a su habilidad de geografía. Es tu forma de corregirla, y no
   serviría de nada si perdiera.
3. **Habilidades** — cuentas, capitales, conversiones, fechas, texto, azar.
4. **Base de conocimiento** — por similitud, con las tres bandas de confianza.

## Cómo funciona

```
texto del usuario
      │
      ├─► skills deterministas ──────────► respuesta exacta
      │   (cuentas, fecha, azar, memoria, aprendizaje)
      │
      └─► recuperación por similitud
          normalizar → corregir erratas → TF-IDF → coseno vs. cada patrón
                                                        │
                        confianza ≥ 0.42 ──────────────► responde
                        0.20 ≤ confianza < 0.42 ───────► responde, pero avisa que duda
                        confianza < 0.20 ─────────────► "no sé" + ofrece aprender
```

**No hay red neuronal.** Rama representa cada frase como un vector disperso TF-IDF que
mezcla tres tipos de rasgos:

1. **raíces de palabra** (un stemmer del español recorta sufijos, así "programar",
   "programando" y "programas" caen en el mismo cubo);
2. **bigramas de palabra**, para distinguir "no puedo" de "puedo";
3. **trigramas de letra**, con menos peso, como red de seguridad ante erratas.

Antes de vectorizar, un corrector por **distancia Damerau-Levenshtein** acerca las
palabras desconocidas al vocabulario conocido: la transposición cuenta como una sola
edición, así que `pyhton` → `python`. Después compara por **similitud coseno** contra
todos los patrones y se queda con la mejor intención.

Lo importante está en la última banda: **por debajo del umbral, Rama dice que no sabe**
en vez de inventar. Un modelo chico que alucina es peor que uno que se calla.

## Estructura

```
rama/                 la versión de escritorio (Python)
  texto.py          normalización, stopwords, stemmer del español
  correccion.py     Damerau-Levenshtein + corrector contra el vocabulario
  vectorizador.py   TF-IDF y similitud coseno, a mano
  skills.py         habilidades deterministas (calculadora AST, fecha, azar, memoria)
  memoria.py        persistencia atómica de lo aprendido y del perfil
  cerebro.py        orquesta todo y decide con qué confianza responder
  cli.py            chat de terminal
  servidor.py       API HTTP + interfaz web (sólo stdlib)
  evaluar.py        mide precisión contra un set de frases nunca vistas
data/
  conocimiento.json base de intenciones (editala y Rama aprende al reiniciar)
  evaluacion.json   set de evaluación
web/index.html      interfaz de chat
tests/test_rama.py  38 tests

android/              la app (Kotlin, sin dependencias)
  app/src/main/java/ar/rama/ai/
    motor/            el mismo cerebro portado: Texto, Correccion,
                      Vectorizador, Calculadora, Memoria, Skills, Cerebro
    MainActivity.kt   el chat, el modo pensar y los adjuntos
    AnalizadorAdjuntos.kt  fotos, PDFs y videos
    Ui.kt             paleta y drawables generados en código
  app/src/test/       38 tests del motor, en la JVM
```

## En el teléfono: la app Android

`android/` es una app nativa de Android con el mismo motor portado a Kotlin,
**sin AndroidX, sin Material, sin una sola librería de terceros**: sólo el
framework. El APK pesa 876 KB y no pide ningún permiso — los
archivos llegan por el selector del sistema (SAF), así que Rama sólo ve lo que
vos le pasás.

### Bajar el APK

Cada push construye el APK en GitHub Actions y lo publica acá:

**https://github.com/ramiroarrojo2077-beep/Apx/releases/download/apk/rama-ai.apk**

Está firmado con la clave *debug* de Android: para instalarlo hay que permitir
"instalar apps de orígenes desconocidos" en el teléfono. Es un build de
desarrollo, no una publicación de Play Store.

### Construirlo vos

```bash
cd android
./gradlew testDebugUnitTest   # 38 tests del motor, en la JVM
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

Requiere JDK 17 y el SDK de Android (`compileSdk 34`). `minSdk 26`, o sea
Android 8.0 en adelante.

### Modo pensar

El botón 🧠 del encabezado despliega, antes de cada respuesta, el razonamiento
real del motor paso a paso:

```
NORMALIZACIÓN        «¿Qué es Pyhton?» → «que es pyhton»
                     tokens: pyhton
CORRECCIÓN           «que es pyhton» → «que es python»
HABILIDADES          ninguna de las 6 habilidades aplicó
VECTORIZACIÓN        41 rasgos: 1 raíz, 0 bigramas, 40 trigramas
SIMILITUD COSENO     0.544  python
                     0.061  que es tfidf
DECISIÓN             confianza 0.544 ≥ 0.42 → respondo directo
```

No es una animación decorativa: son los pasos que el motor ejecutó, con los
números que realmente usó para decidir. Se despliegan con un ritmo de ~230 ms
para que se puedan leer, y quedan plegados bajo la respuesta.

### Fotos, PDFs y videos

Con **＋** (o compartiéndole un archivo desde otra app) Rama analiza:

| Tipo | Qué extrae |
|---|---|
| **Foto** | dimensiones, megapíxeles, proporción, color medio y luminosidad reales (promedia los píxeles), y el EXIF: cámara, fecha, apertura, exposición, ISO |
| **PDF** | cantidad de páginas, tamaño de hoja (A4, Carta, A3…), orientación y una miniatura de la portada renderizada con `PdfRenderer` |
| **Video** | duración, resolución y etiqueta de calidad (HD/Full HD/4K), rotación, bitrate, cuadros por segundo y un fotograma del 10% |

Después podés preguntarle "¿cuántas páginas tenía el PDF?" y responde con lo
que analizó.

**El límite, dicho de frente:** Rama lee el archivo, no lo entiende. Sabe que
tu foto es vertical, de 12 MP y predominantemente azul; no sabe que hay un
perro en ella. Del PDF cuenta las páginas y dibuja la portada, pero no extrae
el texto. Para eso haría falta un modelo de visión, y eso no entra en un APK
de 876 KB que funciona sin internet.


## Enseñarle cosas

Dos formas:

- **En caliente**, desde el chat: `aprende: cuándo es el asado = el sábado`.
  Se guarda en disco y el índice se reconstruye al instante.
- **En frío**, editando `data/conocimiento.json`: agregá una intención con sus
  `patrones` (varias formas de preguntar) y sus `respuestas` (Rama rota entre ellas
  para no sonar a loop).

```json
{
  "id": "mate",
  "patrones": ["cómo se ceba un mate", "cebame un mate", "el mate va con azúcar"],
  "respuestas": ["Agua a 80°C, nunca hirviendo. Y el azúcar es decisión personal."]
}
```

## Límites (a propósito)

Rama no genera texto nuevo: elige entre respuestas escritas. No sabe nada que no esté en
su base o que no le hayas enseñado, no accede a internet y no tiene modelo de mundo.
Es una IA clásica —simbólica y estadística—, chica, explicable y auditable línea por línea.
