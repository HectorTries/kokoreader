package com.kokoreader

/** JNI bridge to espeak-ng G2P. Native lib "espeak_bridge" (see src/main/cpp). */
object EspeakBridge {
    /** @param dataPath extracted espeak-data dir (filesDir), or null for default path. */
    external fun init(dataPath: String?): Boolean
    external fun textToPhonemeIds(text: String): LongArray

    init {
        try {
            System.loadLibrary("espeak_bridge")
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.w("EspeakBridge", "native lib not bundled yet: ${e.message}")
        }
    }
}
