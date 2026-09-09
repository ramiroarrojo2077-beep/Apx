// Puente JNI entre Rama y llama.cpp.
//
// Todo lo que sigue corre dentro del teléfono: carga un modelo GGUF, arma el
// prompt con la plantilla de chat del propio modelo y genera token por token,
// entregándolos a Kotlin a medida que salen.

#include <jni.h>
#include <sys/auxv.h>

#include <algorithm>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

// El .so se compila para armv8.2 con dotprod y fp16: eso es lo que hace que un
// modelo cuantizado ande a velocidad usable. Un teléfono anterior a 2018 no
// tiene esas instrucciones y se caería con SIGILL en el primer producto punto,
// así que preguntamos antes de tocar nada.
#ifndef HWCAP_ASIMDDP
#define HWCAP_ASIMDDP (1 << 20)
#endif
#ifndef HWCAP_ASIMDHP
#define HWCAP_ASIMDHP (1 << 10)
#endif

namespace {

/** Un modelo cargado y listo para generar. */
struct Sesion {
    llama_model *        modelo       = nullptr;
    llama_context *      ctx          = nullptr;
    const llama_vocab *  vocab        = nullptr;
    llama_sampler *      muestreador  = nullptr;
    std::mutex           candado;
    bool                 cancelar     = false;

    /**
     * Los tokens que en este momento están dentro de la caché de atención,
     * en orden y uno por posición.
     *
     * Sirve para no volver a procesar lo que ya se procesó: entre dos turnos
     * de la misma charla, el system prompt y todo el historial anterior son
     * idénticos, y sólo cambia la cola. Comparando esta lista con el prompt
     * nuevo sabemos exactamente desde dónde hay que seguir.
     */
    std::vector<llama_token> en_cache;
};

Sesion * sesion_de(jlong handle) {
    return reinterpret_cast<Sesion *>(handle);
}

/**
 * Devuelve cuántos bytes del principio forman UTF-8 completo.
 *
 * Un token puede cortar una letra al medio: "ñ" y "á" ocupan dos bytes. Si
 * entregáramos el fragmento suelto, Java lo convertiría en basura, así que
 * guardamos la cola incompleta hasta que llegue el resto.
 */
size_t prefijo_utf8_completo(const std::string & bytes) {
    size_t i = 0;
    while (i < bytes.size()) {
        const unsigned char c = static_cast<unsigned char>(bytes[i]);
        size_t largo;
        if (c < 0x80)             largo = 1;
        else if ((c >> 5) == 0x6) largo = 2;
        else if ((c >> 4) == 0xE) largo = 3;
        else if ((c >> 3) == 0x1E) largo = 4;
        else                      largo = 1;  // byte inválido: lo dejamos pasar

        if (i + largo > bytes.size()) return i;  // secuencia cortada al final
        i += largo;
    }
    return i;
}

std::string texto_del_token(const llama_vocab * vocab, llama_token token) {
    char buffer[256];
    int32_t n = llama_token_to_piece(vocab, token, buffer, sizeof(buffer), 0, false);
    if (n < 0) {
        std::vector<char> grande(-n);
        n = llama_token_to_piece(vocab, token, grande.data(), static_cast<int32_t>(grande.size()), 0, false);
        return n > 0 ? std::string(grande.data(), n) : std::string();
    }
    return std::string(buffer, n);
}

std::vector<llama_token> tokenizar(const llama_vocab * vocab, const std::string & texto, bool especiales) {
    const int32_t maximo = static_cast<int32_t>(texto.size()) + 16;
    std::vector<llama_token> tokens(maximo);
    const int32_t n = llama_tokenize(vocab, texto.c_str(), static_cast<int32_t>(texto.size()),
                                     tokens.data(), maximo, especiales, true);
    if (n < 0) {
        tokens.resize(-n);
        llama_tokenize(vocab, texto.c_str(), static_cast<int32_t>(texto.size()),
                       tokens.data(), -n, especiales, true);
    } else {
        tokens.resize(n);
    }
    return tokens;
}

/**
 * Crea un String de Java a partir de UTF-8 de verdad.
 *
 * No se puede usar NewStringUTF: JNI espera "UTF-8 modificado", donde los
 * emojis y todo lo que está fuera del plano básico se codifica como pareja
 * subrogada de 6 bytes, no como la secuencia de 4 que produce un modelo.
 * Pasarle esos 4 bytes rompe la máquina virtual. Convertimos a UTF-16 a mano.
 */
jstring nueva_cadena(JNIEnv * env, const std::string & utf8) {
    std::vector<jchar> utf16;
    utf16.reserve(utf8.size());

    size_t i = 0;
    while (i < utf8.size()) {
        const unsigned char c = static_cast<unsigned char>(utf8[i]);
        uint32_t punto;
        size_t largo;

        if (c < 0x80)              { punto = c;        largo = 1; }
        else if ((c >> 5) == 0x6)  { punto = c & 0x1F; largo = 2; }
        else if ((c >> 4) == 0xE)  { punto = c & 0x0F; largo = 3; }
        else if ((c >> 3) == 0x1E) { punto = c & 0x07; largo = 4; }
        else                       { i++; continue; }  // byte suelto: se descarta

        if (i + largo > utf8.size()) break;
        for (size_t j = 1; j < largo; j++) {
            punto = (punto << 6) | (static_cast<unsigned char>(utf8[i + j]) & 0x3F);
        }
        i += largo;

        if (punto < 0x10000) {
            utf16.push_back(static_cast<jchar>(punto));
        } else {
            punto -= 0x10000;
            utf16.push_back(static_cast<jchar>(0xD800 + (punto >> 10)));
            utf16.push_back(static_cast<jchar>(0xDC00 + (punto & 0x3FF)));
        }
    }
    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

std::string desde_jstring(JNIEnv * env, jstring texto) {
    if (texto == nullptr) return {};
    const char * crudo = env->GetStringUTFChars(texto, nullptr);
    std::string copia = crudo ? crudo : "";
    if (crudo) env->ReleaseStringUTFChars(texto, crudo);
    return copia;
}

/** Silencia los logs de llama.cpp: en un teléfono no los ve nadie. */
void sin_logs(ggml_log_level, const char *, void *) {}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_ar_rama_ai_motor_Llama_nativeIniciar(JNIEnv *, jobject) {
    llama_log_set(sin_logs, nullptr);
    llama_backend_init();
}

/**
 * ¿El procesador de este teléfono entiende las instrucciones con las que se
 * compiló la librería?
 *
 * Devuelve la lista de las que faltan, separadas por coma, o vacío si están
 * todas. Preguntar es baratísimo y evita el peor final posible: la app
 * cerrándose de golpe en mitad de una respuesta, sin explicación.
 */
JNIEXPORT jstring JNICALL
Java_ar_rama_ai_motor_Llama_nativeFaltantes(JNIEnv * env, jobject) {
    const unsigned long caps = getauxval(AT_HWCAP);
    std::string faltan;
    if (!(caps & HWCAP_ASIMDDP)) faltan += "dotprod";
    if (!(caps & HWCAP_ASIMDHP)) { if (!faltan.empty()) faltan += ", "; faltan += "fp16"; }
    return nueva_cadena(env, faltan);
}

JNIEXPORT jlong JNICALL
Java_ar_rama_ai_motor_Llama_nativeAbrir(JNIEnv * env, jobject, jstring ruta, jint n_ctx, jint n_hilos) {
    const std::string archivo = desde_jstring(env, ruta);

    llama_model_params parametros_modelo = llama_model_default_params();
    parametros_modelo.n_gpu_layers = 0;  // CPU: es lo único garantizado en Android

    llama_model * modelo = llama_model_load_from_file(archivo.c_str(), parametros_modelo);
    if (modelo == nullptr) return 0;

    llama_context_params parametros_ctx = llama_context_default_params();
    parametros_ctx.n_ctx           = static_cast<uint32_t>(n_ctx);
    // Lotes chicos: el buffer de cómputo crece con ellos, y en un teléfono
    // cada megabyte de más acerca el cierre por falta de memoria.
    parametros_ctx.n_batch         = 256;
    parametros_ctx.n_ubatch        = 256;
    parametros_ctx.n_threads       = n_hilos;
    parametros_ctx.n_threads_batch = n_hilos;

    // La caché de atención en 8 bits ocupa la mitad que en 16, y en un modelo
    // grande esa mitad son cientos de megas. La pérdida de calidad es
    // despreciable comparada con la de los propios pesos, ya cuantizados.
    parametros_ctx.type_k          = GGML_TYPE_Q8_0;
    parametros_ctx.type_v          = GGML_TYPE_Q8_0;
    parametros_ctx.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;

    llama_context * ctx = llama_init_from_model(modelo, parametros_ctx);
    if (ctx == nullptr) {
        // No todos los modelos aceptan la caché cuantizada; si no, se usa la
        // normal antes que dejar al usuario sin poder cargar nada.
        parametros_ctx.type_k          = GGML_TYPE_F16;
        parametros_ctx.type_v          = GGML_TYPE_F16;
        parametros_ctx.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
        ctx = llama_init_from_model(modelo, parametros_ctx);
    }
    if (ctx == nullptr) {
        llama_model_free(modelo);
        return 0;
    }

    Sesion * sesion = new Sesion();
    sesion->modelo = modelo;
    sesion->ctx    = ctx;
    sesion->vocab  = llama_model_get_vocab(modelo);
    return reinterpret_cast<jlong>(sesion);
}

JNIEXPORT void JNICALL
Java_ar_rama_ai_motor_Llama_nativeCerrar(JNIEnv *, jobject, jlong handle) {
    Sesion * sesion = sesion_de(handle);
    if (sesion == nullptr) return;
    if (sesion->muestreador) llama_sampler_free(sesion->muestreador);
    if (sesion->ctx)         llama_free(sesion->ctx);
    if (sesion->modelo)      llama_model_free(sesion->modelo);
    delete sesion;
}

JNIEXPORT jstring JNICALL
Java_ar_rama_ai_motor_Llama_nativeInfo(JNIEnv * env, jobject, jlong handle) {
    Sesion * sesion = sesion_de(handle);
    if (sesion == nullptr) return nueva_cadena(env, "");

    char descripcion[256] = {0};
    llama_model_desc(sesion->modelo, descripcion, sizeof(descripcion));

    const uint64_t parametros = llama_model_n_params(sesion->modelo);
    const uint32_t contexto   = llama_n_ctx(sesion->ctx);

    std::string info = std::string(descripcion) +
                       " · " + std::to_string(parametros / 1000000) + "M parámetros" +
                       " · contexto " + std::to_string(contexto);
    return nueva_cadena(env, info);
}

/**
 * Arma el prompt con la plantilla de chat que trae el propio modelo.
 * Devuelve vacío si el GGUF no incluye plantilla: ahí Kotlin usa la suya.
 */
JNIEXPORT jstring JNICALL
Java_ar_rama_ai_motor_Llama_nativeFormatearChat(JNIEnv * env, jobject, jlong handle,
                                                jobjectArray roles, jobjectArray contenidos,
                                                jboolean agregar_asistente) {
    Sesion * sesion = sesion_de(handle);
    if (sesion == nullptr) return nueva_cadena(env, "");

    const char * plantilla = llama_model_chat_template(sesion->modelo, nullptr);
    if (plantilla == nullptr) return nueva_cadena(env, "");

    const jsize cantidad = env->GetArrayLength(roles);
    std::vector<std::string> textos_rol(cantidad);
    std::vector<std::string> textos_contenido(cantidad);
    std::vector<llama_chat_message> mensajes(cantidad);

    for (jsize i = 0; i < cantidad; i++) {
        jstring rol = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
        jstring contenido = static_cast<jstring>(env->GetObjectArrayElement(contenidos, i));
        textos_rol[i]       = desde_jstring(env, rol);
        textos_contenido[i] = desde_jstring(env, contenido);
        env->DeleteLocalRef(rol);
        env->DeleteLocalRef(contenido);
        mensajes[i].role    = textos_rol[i].c_str();
        mensajes[i].content = textos_contenido[i].c_str();
    }

    std::vector<char> buffer(8192);
    int32_t n = llama_chat_apply_template(plantilla, mensajes.data(), mensajes.size(),
                                          agregar_asistente == JNI_TRUE,
                                          buffer.data(), static_cast<int32_t>(buffer.size()));
    if (n > static_cast<int32_t>(buffer.size())) {
        buffer.resize(n);
        n = llama_chat_apply_template(plantilla, mensajes.data(), mensajes.size(),
                                      agregar_asistente == JNI_TRUE,
                                      buffer.data(), static_cast<int32_t>(buffer.size()));
    }
    if (n < 0) return nueva_cadena(env, "");
    return nueva_cadena(env, std::string(buffer.data(), n));
}

JNIEXPORT void JNICALL
Java_ar_rama_ai_motor_Llama_nativeCancelar(JNIEnv *, jobject, jlong handle) {
    Sesion * sesion = sesion_de(handle);
    if (sesion != nullptr) sesion->cancelar = true;
}

/**
 * Genera la respuesta token por token.
 *
 * Cada fragmento se entrega a `receptor.onToken(String)`; si ese método
 * devuelve false, la generación se corta. Devuelve cuántos tokens produjo,
 * o un número negativo si algo falló.
 */
JNIEXPORT jint JNICALL
Java_ar_rama_ai_motor_Llama_nativeGenerar(JNIEnv * env, jobject, jlong handle, jstring prompt,
                                          jint max_tokens, jfloat temperatura, jfloat top_p,
                                          jint top_k, jlong semilla, jobject receptor) {
    Sesion * sesion = sesion_de(handle);
    if (sesion == nullptr) return -1;

    std::lock_guard<std::mutex> bloqueo(sesion->candado);
    sesion->cancelar = false;

    jclass clase = env->GetObjectClass(receptor);
    jmethodID on_token = env->GetMethodID(clase, "onToken", "(Ljava/lang/String;)Z");
    if (on_token == nullptr) return -2;

    if (sesion->muestreador) llama_sampler_free(sesion->muestreador);
    sesion->muestreador = llama_sampler_chain_init(llama_sampler_chain_default_params());
    // El top-k va primero: penalizar sobre el vocabulario entero es lento.
    llama_sampler_chain_add(sesion->muestreador, llama_sampler_init_top_k(top_k));
    // Sin penalización, un modelo chico se traba repitiendo la misma frase.
    llama_sampler_chain_add(sesion->muestreador, llama_sampler_init_penalties(
        llama_vocab_n_tokens(sesion->vocab), 64, 1.12f, 0.0f, 0.0f));
    llama_sampler_chain_add(sesion->muestreador, llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(sesion->muestreador, llama_sampler_init_temp(temperatura));
    llama_sampler_chain_add(sesion->muestreador, llama_sampler_init_dist(static_cast<uint32_t>(semilla)));

    const std::string texto = desde_jstring(env, prompt);
    std::vector<llama_token> tokens = tokenizar(sesion->vocab, texto, true);
    if (tokens.empty()) return -3;

    const uint32_t contexto = llama_n_ctx(sesion->ctx);
    if (tokens.size() + 8 >= contexto) {
        // El prompt no entra: recortamos por el principio, que es lo más viejo.
        tokens.erase(tokens.begin(), tokens.begin() + (tokens.size() + 8 - contexto));
    }

    // Reaprovechamos todo lo que ya está en la caché.
    //
    // El prompt de un turno cualquiera empieza igual que el del turno anterior:
    // mismas instrucciones, mismo historial. Buscamos hasta dónde coinciden y
    // tiramos sólo lo que sigue, en vez de vaciar todo y volver a leer miles de
    // tokens que no cambiaron. En una charla larga esto es la diferencia entre
    // esperar varios segundos antes de la primera palabra y que salga sola.
    llama_memory_t memoria = llama_get_memory(sesion->ctx);

    // El último token siempre se decodifica: de ahí salen los logits con los
    // que se elige la primera palabra de la respuesta.
    const size_t tope_comun = tokens.size() - 1;
    size_t comun = 0;
    while (comun < tope_comun && comun < sesion->en_cache.size() &&
           sesion->en_cache[comun] == tokens[comun]) {
        comun++;
    }

    if (comun == 0 || !llama_memory_seq_rm(memoria, 0, static_cast<llama_pos>(comun), -1)) {
        llama_memory_clear(memoria, true);
        sesion->en_cache.clear();
        comun = 0;
    } else {
        sesion->en_cache.resize(comun);
    }

    // Un prompt más largo que el lote máximo hace abortar a llama_decode, y
    // con el sistema, el contexto y la búsqueda se pasa de 512 sin esfuerzo.
    const size_t tope_lote = llama_n_batch(sesion->ctx);
    for (size_t desde = comun; desde < tokens.size(); desde += tope_lote) {
        const size_t cuantos = std::min(tope_lote, tokens.size() - desde);
        llama_batch lote = llama_batch_get_one(tokens.data() + desde, static_cast<int32_t>(cuantos));
        if (llama_decode(sesion->ctx, lote) != 0) {
            // Quedó a medio llenar: no sabemos qué hay adentro, la vaciamos.
            llama_memory_clear(memoria, true);
            sesion->en_cache.clear();
            return -4;
        }
        sesion->en_cache.insert(sesion->en_cache.end(), tokens.begin() + static_cast<long>(desde),
                                tokens.begin() + static_cast<long>(desde + cuantos));
    }

    std::string pendiente;   // bytes UTF-8 que todavía no forman una letra
    int generados = 0;
    size_t usados = tokens.size();

    for (int i = 0; i < max_tokens; i++) {
        if (usados + 1 >= contexto) break;  // se acabó la ventana de contexto
        if (sesion->cancelar) break;

        const llama_token token = llama_sampler_sample(sesion->muestreador, sesion->ctx, -1);
        if (llama_vocab_is_eog(sesion->vocab, token)) break;

        pendiente += texto_del_token(sesion->vocab, token);
        const size_t completo = prefijo_utf8_completo(pendiente);
        if (completo > 0) {
            jstring fragmento = nueva_cadena(env, pendiente.substr(0, completo));
            const jboolean seguir = env->CallBooleanMethod(receptor, on_token, fragmento);
            env->DeleteLocalRef(fragmento);
            if (env->ExceptionCheck()) { env->ExceptionClear(); break; }
            pendiente.erase(0, completo);
            if (seguir != JNI_TRUE) break;
        }

        generados++;
        usados++;
        llama_token siguiente = token;
        llama_batch lote_uno = llama_batch_get_one(&siguiente, 1);
        if (llama_decode(sesion->ctx, lote_uno) != 0) {
            llama_memory_clear(memoria, true);
            sesion->en_cache.clear();
            break;
        }
        // El token generado también queda en la caché: el próximo turno lo va a
        // encontrar en el historial y no va a tener que releerlo.
        sesion->en_cache.push_back(siguiente);
    }

    return generados;
}

}  // extern "C"
