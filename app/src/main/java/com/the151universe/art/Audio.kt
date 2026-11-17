package com.the151universe.art

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Il volume del suono, campione per campione. Due usi:
 * - export: [curva] dice il guadagno a ogni istante (ms dall'inizio del pezzo, nel video finito);
 *   Media3 applica la velocita' PRIMA dei nostri processori, quindi il tempo contato qui e' gia' quello finale;
 * - anteprima: [curva] null, conta [adesso], cambiato dall'orologio dell'editor.
 * Gestisce PCM 16 bit e float; sopra 1 (fino a 200%) il suono si alza davvero, tagliato ai limiti.
 */
@OptIn(UnstableApi::class)
class Guadagno(private val curva: ((Long) -> Float)? = null) : BaseAudioProcessor() {
    @Volatile var adesso = 1f
    private var frame = 0L
    private var hz = 44100
    private var canali = 2
    private var float = false

    override fun onConfigure(f: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (f.encoding != C.ENCODING_PCM_16BIT && f.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(f)
        }
        hz = f.sampleRate.coerceAtLeast(1); canali = f.channelCount.coerceAtLeast(1); float = f.encoding == C.ENCODING_PCM_FLOAT
        return f
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) { frame = 0 }
    override fun onReset() { frame = 0 }

    override fun queueInput(input: ByteBuffer) {
        val n = input.remaining()
        if (n == 0) return
        val out = replaceOutputBuffer(n)
        val src = input.order(ByteOrder.nativeOrder())
        val bytes = if (float) 4 else 2
        val frames = n / (bytes * canali)
        // la curva si chiede ogni 64 campioni (~1,5 ms): abbastanza fine per dissolvenze e rampe
        var g = 1f
        for (k in 0 until frames) {
            if (k % 64 == 0) g = curva?.invoke((frame + k) * 1000L / hz) ?: adesso
            for (ch in 0 until canali) {
                if (float) {
                    out.putFloat((src.float * g).coerceIn(-1f, 1f))
                } else {
                    out.putShort((src.short * g).toInt().coerceIn(-32768, 32767).toShort())
                }
            }
        }
        // avanzi (frame incompleto): passano com'erano
        while (src.hasRemaining()) out.put(src.get())
        frame += frames
        out.flip()
    }
}
