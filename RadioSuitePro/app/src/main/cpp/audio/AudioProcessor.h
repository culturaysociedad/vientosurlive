#pragma once

#include "AudioBlock.h"

namespace radiosuite::audio {

// Interfaz común de todos los eslabones del pipeline DSP.
// process() se invoca en el hilo de audio de tiempo real: sin locks, sin I/O,
// sin alocaciones de heap. Parámetros mutables se actualizan atómicamente vía
//.setParameter() desde el hilo de la UI/controlador.
class AudioProcessor {
public:
    virtual ~AudioProcessor() = default;

    // Procesa el bloque in-place. Devuelve true si el bloque debe seguir la cadena.
    virtual bool process(AudioBlock& block) = 0;

    // Reinicia estado interno (envolventes, filtros, buffers) sin realloc.
    virtual void reset() = 0;

    // Nombre para diagnóstico / logging.
    virtual const char* name() const = 0;
};

} // namespace radiosuite::audio
