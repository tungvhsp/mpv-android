#define UTIL_EXTERN
#include "jni_utils.h"

#include <jni.h>
#include <stdlib.h>
#include <string>

static void utf8_append_codepoint(std::string &out, uint32_t cp)
{
    if (cp <= 0x7F) {
        out.push_back((char)cp);
    } else if (cp <= 0x7FF) {
        out.push_back((char)(0xC0 | (cp >> 6)));
        out.push_back((char)(0x80 | (cp & 0x3F)));
    } else if (cp <= 0xFFFF) {
        out.push_back((char)(0xE0 | (cp >> 12)));
        out.push_back((char)(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back((char)(0x80 | (cp & 0x3F)));
    } else {
        out.push_back((char)(0xF0 | (cp >> 18)));
        out.push_back((char)(0x80 | ((cp >> 12) & 0x3F)));
        out.push_back((char)(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back((char)(0x80 | (cp & 0x3F)));
    }
}

static std::string sanitize_utf8(const char *input)
{
    std::string out;
    if (!input)
        return out;

    const unsigned char *p = (const unsigned char *)input;
    while (*p) {
        uint32_t cp = 0;
        const unsigned char c = *p;

        if (c < 0x80) {
            cp = c;
            p++;
        } else if ((c & 0xE0) == 0xC0) {
            if (p[1] && (p[1] & 0xC0) == 0x80 && c >= 0xC2) {
                cp = ((c & 0x1F) << 6) | (p[1] & 0x3F);
                if (cp >= 0x80)
                    p += 2;
                else
                    cp = '?', p++;
            } else {
                cp = '?';
                p++;
            }
        } else if ((c & 0xF0) == 0xE0) {
            if (p[1] && p[2] && (p[1] & 0xC0) == 0x80 && (p[2] & 0xC0) == 0x80 &&
                (c != 0xE0 || p[1] >= 0xA0) && (c != 0xED || p[1] < 0xA0)) {
                cp = ((c & 0x0F) << 12) | ((p[1] & 0x3F) << 6) | (p[2] & 0x3F);
                if (cp >= 0x800 && !(cp >= 0xD800 && cp <= 0xDFFF))
                    p += 3;
                else
                    cp = '?', p++;
            } else {
                cp = '?';
                p++;
            }
        } else if ((c & 0xF8) == 0xF0) {
            if (p[1] && p[2] && p[3] && (p[1] & 0xC0) == 0x80 && (p[2] & 0xC0) == 0x80 &&
                (p[3] & 0xC0) == 0x80 && (c != 0xF0 || p[1] >= 0x90) && (c <= 0xF3)) {
                cp = ((c & 0x07) << 18) | ((p[1] & 0x3F) << 12) |
                     ((p[2] & 0x3F) << 6) | (p[3] & 0x3F);
                if (cp >= 0x10000 && cp <= 0x10FFFF)
                    p += 4;
                else
                    cp = '?', p++;
            } else {
                cp = '?';
                p++;
            }
        } else {
            cp = '?';
            p++;
        }

        utf8_append_codepoint(out, cp);
    }

    return out;
}

jstring new_string_utf8(JNIEnv *env, const char *str)
{
    if (!str)
        return NULL;

    const std::string safe = sanitize_utf8(str);
    return env->NewStringUTF(safe.c_str());
}

bool acquire_jni_env(JavaVM *vm, JNIEnv **env)
{
    int ret = vm->GetEnv((void**) env, JNI_VERSION_1_6);
    if (ret == JNI_EDETACHED)
        return vm->AttachCurrentThread(env, NULL) == 0;
    else
        return ret == JNI_OK;
}

// Apparently it's considered slow to FindClass and GetMethodID every time we need them,
// so let's have a nice cache here.

void init_methods_cache(JNIEnv *env)
{
    static bool methods_initialized = false;
    if (methods_initialized)
        return;

    #define FIND_CLASS(name) reinterpret_cast<jclass>(env->NewGlobalRef(env->FindClass(name)))
    java_Integer = FIND_CLASS("java/lang/Integer");
    java_Integer_init = env->GetMethodID(java_Integer, "<init>", "(I)V");
    java_Double = FIND_CLASS("java/lang/Double");
    java_Double_init = env->GetMethodID(java_Double, "<init>", "(D)V");
    java_Boolean = FIND_CLASS("java/lang/Boolean");
    java_Boolean_init = env->GetMethodID(java_Boolean, "<init>", "(Z)V");

    android_graphics_Bitmap = FIND_CLASS("android/graphics/Bitmap");
    // createBitmap(int[], int, int, android.graphics.Bitmap$Config)
    android_graphics_Bitmap_createBitmap = env->GetStaticMethodID(android_graphics_Bitmap, "createBitmap", "([IIILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    android_graphics_Bitmap_Config = FIND_CLASS("android/graphics/Bitmap$Config");
    // static final android.graphics.Bitmap$Config ARGB_8888
    android_graphics_Bitmap_Config_ARGB_8888 = env->GetStaticFieldID(android_graphics_Bitmap_Config, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");

    mpv_MPVLib = FIND_CLASS("is/xyz/mpv/MPVLib");
    mpv_MPVLib_eventProperty_S  = env->GetStaticMethodID(mpv_MPVLib, "eventProperty", "(Ljava/lang/String;)V"); // eventProperty(String)
    mpv_MPVLib_eventProperty_Sb = env->GetStaticMethodID(mpv_MPVLib, "eventProperty", "(Ljava/lang/String;Z)V"); // eventProperty(String, boolean)
    mpv_MPVLib_eventProperty_Sl = env->GetStaticMethodID(mpv_MPVLib, "eventProperty", "(Ljava/lang/String;J)V"); // eventProperty(String, long)
    mpv_MPVLib_eventProperty_Sd = env->GetStaticMethodID(mpv_MPVLib, "eventProperty", "(Ljava/lang/String;D)V"); // eventProperty(String, double)
    mpv_MPVLib_eventProperty_SS = env->GetStaticMethodID(mpv_MPVLib, "eventProperty", "(Ljava/lang/String;Ljava/lang/String;)V"); // eventProperty(String, String)
    mpv_MPVLib_event = env->GetStaticMethodID(mpv_MPVLib, "event", "(I)V"); // event(int)
    mpv_MPVLib_logMessage_SiS = env->GetStaticMethodID(mpv_MPVLib, "logMessage", "(Ljava/lang/String;ILjava/lang/String;)V"); // logMessage(String, int, String)
    #undef FIND_CLASS

    methods_initialized = true;
}
