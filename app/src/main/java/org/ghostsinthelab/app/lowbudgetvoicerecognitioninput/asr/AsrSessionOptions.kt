package org.ghostsinthelab.app.lowbudgetvoicerecognitioninput.asr

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import java.io.File
import java.util.EnumSet

/**
 * Opens an ONNX Runtime session with the NNAPI execution provider preferred,
 * falling back to CPU if NNAPI initialisation or session load fails.
 *
 * The q4f16 Gemma bundle uses custom ops (`GatherBlockQuantized`,
 * `GroupQueryAttention` from the `com.microsoft` domain) that NNAPI doesn't
 * support. ORT partitions the graph: NNAPI-compatible subgraphs run on the
 * accelerator, the rest stays on CPU. Partitioning overhead can sometimes
 * outweigh the acceleration, so the caller should measure both modes.
 */
object AsrSessionOptions {

    /**
     * Returns (session, execution-provider description string for logs).
     * When [useNnapi] is true and NNAPI fails, the failure reason is included
     * in the description so it surfaces in the debug log.
     */
    fun openSession(
        env: OrtEnvironment,
        modelFile: File,
        useNnapi: Boolean = false,
    ): Pair<OrtSession, String> {
        if (useNnapi) {
            val opts = buildCpuOptions()
            try {
                opts.addNnapi(EnumSet.of(NNAPIFlags.USE_FP16))
                val session = env.createSession(modelFile.absolutePath, opts)
                return session to "NNAPI (FP16)"
            } catch (t: Throwable) {
                opts.close()
                return openCpu(env, modelFile) to "NNAPI failed (${t.message}); CPU"
            }
        }
        return openCpu(env, modelFile) to "CPU"
    }

    private fun openCpu(env: OrtEnvironment, modelFile: File): OrtSession {
        return env.createSession(modelFile.absolutePath, buildCpuOptions())
    }

    /**
     * Peak memory matters more than throughput on our 5 GB target device:
     *   - `setMemoryPatternOptimization(false)` disables the allocator's
     *     dynamic-shape prediction that tends to over-allocate arenas.
     *   - `setCPUArenaAllocator(false)` returns memory to the system
     *     between tensors instead of keeping it pooled.
     * Both are known to shave hundreds of MB off ORT peak RSS on Whisper-
     * family models at the cost of ~10-30% throughput.
     */
    private fun buildCpuOptions(): OrtSession.SessionOptions {
        return OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            setMemoryPatternOptimization(false)
            setCPUArenaAllocator(false)
        }
    }
}
