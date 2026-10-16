#include <jni.h>
#include <android/bitmap.h>

#include "image_processing.h"

namespace {

struct Locked {
    JNIEnv* env;
    jobject bmp;
    AndroidBitmapInfo info{};
    void* px = nullptr;
    bool ok = false;

    Locked(JNIEnv* e, jobject b) : env(e), bmp(b) {
        if (AndroidBitmap_getInfo(e, b, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return;
        if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return;
        if (AndroidBitmap_lockPixels(e, b, &px) != ANDROID_BITMAP_RESULT_SUCCESS || !px) return;
        ok = true;
    }
    ~Locked() {
        if (ok) AndroidBitmap_unlockPixels(env, bmp);
    }
};

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL Java_com_camera_app_Native_enhance(JNIEnv* env, jclass, jobject bmp, jfloat denoise,
                                                              jfloat clahe, jfloat sharpen, jfloat awb, jfloat gamma) {
    Locked l(env, bmp);
    if (!l.ok) return JNI_FALSE;
    cam::EnhanceParams p;
    p.denoise = denoise;
    p.clahe = clahe;
    p.sharpen = sharpen;
    p.awb = awb;
    p.gamma = gamma;
    cam::enhance((uint8_t*) l.px, (int) l.info.width, (int) l.info.height, (int) l.info.stride, p);
    return JNI_TRUE;
}

JNIEXPORT jlong JNICALL Java_com_camera_app_Native_mergeBegin(JNIEnv*, jclass, jint w, jint h) {
    if (w < 16 || h < 16) return 0;
    return (jlong) new cam::Merger(w, h);
}

JNIEXPORT jboolean JNICALL Java_com_camera_app_Native_mergeAdd(JNIEnv* env, jclass, jlong handle, jobject bmp) {
    auto* m = (cam::Merger*) handle;
    if (!m) return JNI_FALSE;
    Locked l(env, bmp);
    if (!l.ok || (int) l.info.width != m->width() || (int) l.info.height != m->height()) return JNI_FALSE;
    return m->add((const uint8_t*) l.px, (int) l.info.stride) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_camera_app_Native_mergeEnd(JNIEnv* env, jclass, jlong handle, jobject out) {
    auto* m = (cam::Merger*) handle;
    if (!m) return JNI_FALSE;
    Locked l(env, out);
    if (!l.ok || (int) l.info.width != m->width() || (int) l.info.height != m->height()) return JNI_FALSE;
    m->finish((uint8_t*) l.px, (int) l.info.stride);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_com_camera_app_Native_mergeFree(JNIEnv*, jclass, jlong handle) {
    delete (cam::Merger*) handle;
}

}  // extern "C"
