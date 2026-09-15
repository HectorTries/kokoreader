// KokoReader G2P bridge — real espeak-ng IPA phonemes.
// Links libespeak-ng (arm64-v8a + armeabi-v7a, built from upstream source).
// Data (~850KB English-only) ships in assets/espeak-data, extracted to
// filesDir on first use (espeak needs a real filesystem path).
// Output: Kokoro phone ids via the kokoro-onnx vocab (config.json),
// filtered to in-vocab chars, '_' separators stripped (kokoro-onnx strips
// them too before tokenize — see Tokenizer.phonemize).

#include <jni.h>
#include <string>
#include <vector>
#include <unordered_map>
#include <mutex>

#include "espeak-ng/speak_lib.h"

static std::mutex g_mu;
static bool g_init = false;

// Kokoro vocab: IPA char (UTF-8) -> id. Matches kokoro-onnx config.json.
static const std::unordered_map<std::string,int> VOCAB = {
{";",1},{":",2},{",",3},{".",4},{"!",5},{"?",6},{"—",9},{"…",10},{"\"",11},{"(",12},{")",13},{"“",14},{"”",15},{" ",16},{"̃",17},{"ʣ",18},{"ʥ",19},{"ʦ",20},{"ʨ",21},{"ᵝ",22},{"ꭧ",23},{"A",24},{"I",25},{"O",31},{"Q",33},{"S",35},{"T",36},{"W",39},{"Y",41},{"ᵊ",42},{"a",43},{"b",44},{"c",45},{"d",46},{"e",47},{"f",48},{"h",50},{"i",51},{"j",52},{"k",53},{"l",54},{"m",55},{"n",56},{"o",57},{"p",58},{"q",59},{"r",60},{"s",61},{"t",62},{"u",63},{"v",64},{"w",65},{"x",66},{"y",67},{"z",68},{"ɑ",69},{"ɐ",70},{"ɒ",71},{"æ",72},{"β",75},{"ɔ",76},{"ɕ",77},{"ç",78},{"ɖ",80},{"ð",81},{"ʤ",82},{"ə",83},{"ɚ",85},{"ɛ",86},{"ɜ",87},{"ɟ",90},{"ɡ",92},{"ɥ",99},{"ɨ",101},{"ɪ",102},{"ʝ",103},{"ɯ",110},{"ɰ",111},{"ŋ",112},{"ɳ",113},{"ɲ",114},{"ɴ",115},{"ø",116},{"ɸ",118},{"θ",119},{"œ",120},{"ɹ",123},{"ɾ",125},{"ɻ",126},{"ʁ",128},{"ɽ",129},{"ʂ",130},{"ʃ",131},{"ʈ",132},{"ʧ",133},{"ʊ",135},{"ʋ",136},{"ʌ",138},{"ɣ",139},{"ɤ",140},{"χ",142},{"ʎ",143},{"ʒ",147},{"ʔ",148},{"ˈ",156},{"ˌ",157},{"ː",158},{"ʰ",162},{"ʲ",164},{"↓",169},{"→",171},{"↗",172},{"↘",173},{"ᵻ",177}
};

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_kokoreader_EspeakBridge_init(JNIEnv *env, jobject /*thiz*/, jstring dataPath) {
    std::lock_guard<std::mutex> lock(g_mu);
    if (g_init) return JNI_TRUE;
    const char *dp = dataPath ? env->GetStringUTFChars(dataPath, nullptr) : nullptr;
    std::string path = dp ? dp : "";
    if (dataPath) env->ReleaseStringUTFChars(dataPath, dp);
    // AUDIO_OUTPUT_SYNCHRONOUS (0x02): no audio, phonemes only.
    int rate = espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS, 0, path.empty() ? nullptr : path.c_str(), 0);
    if (rate <= 0) return JNI_FALSE;
    if (espeak_SetVoiceByName("en-us") != EE_OK) return JNI_FALSE;
    g_init = true;
    return JNI_TRUE;
}

// Real G2P: espeak_TextToPhonemes (UTF-8 in, '_' separated IPA out, same
// flags as python phonemizer: separator '_' << 8 | IPA 0x02), then map each
// UTF-8 char through the Kokoro vocab, dropping OOV (mirrors
// kokoro-onnx Tokenizer.phonemize filtering).
JNIEXPORT jlongArray JNICALL
Java_com_kokoreader_EspeakBridge_textToPhonemeIds(JNIEnv *env, jobject /*thiz*/, jstring text) {
    const char *chars = env->GetStringUTFChars(text, nullptr);
    std::string s(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(text, chars);

    std::vector<jlong> ids;
    {
        std::lock_guard<std::mutex> lock(g_mu);
        if (!g_init) {
            int rate = espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS, 0, nullptr, 0);
            if (rate > 0 && espeak_SetVoiceByName("en-us") == EE_OK) g_init = true;
        }
        if (g_init) {
            const char *p = s.c_str();
            const void *vp = (const void *)p;
            std::string ipa;
            // textmode UTF-8=1, phonememode '_' sep + IPA.
            while (vp != nullptr) {
                const char *chunk = espeak_TextToPhonemes(&vp, 1, ('_' << 8) | 0x02);
                if (chunk) { ipa += chunk; ipa += ' '; }
            }
            // Split UTF-8 into code points; drop '_' separators and OOV.
            for (size_t i = 0; i < ipa.size();) {
                unsigned char c = (unsigned char)ipa[i];
                size_t len = 1;
                if ((c & 0x80) == 0) len = 1;
                else if ((c & 0xE0) == 0xC0) len = 2;
                else if ((c & 0xF0) == 0xE0) len = 3;
                else if ((c & 0xF8) == 0xF0) len = 4;
                std::string ch = ipa.substr(i, len);
                i += len;
                if (ch == "_" || ch == " ") {
                    if (ch == " ") {
                        auto it = VOCAB.find(" ");
                        if (it != VOCAB.end()) ids.push_back(it->second);
                    }
                    continue;
                }
                auto it = VOCAB.find(ch);
                if (it != VOCAB.end()) ids.push_back((jlong)it->second);
            }
        }
    }
    // Fallback: never return empty (protects ONNX from [1,0] crash).
    if (ids.empty()) {
        auto it = VOCAB.find(" ");
        ids.push_back(it != VOCAB.end() ? it->second : 16);
    }
    jlongArray out = env->NewLongArray((jsize)ids.size());
    if (!ids.empty()) env->SetLongArrayRegion(out, 0, (jsize)ids.size(), ids.data());
    return out;
}

}  // extern "C"
