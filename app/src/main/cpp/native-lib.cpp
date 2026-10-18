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

JNIEXPORT jboolean JNICALL Java_com_camera_app_Native_enhanceEx(JNIEnv* env, jclass, jobject bmp, jfloatArray arr) {
    if (!arr || env->GetArrayLength(arr) < 10) return JNI_FALSE;
    float v[10];
    env->GetFloatArrayRegion(arr, 0, 10, v);
    Locked l(env, bmp);
    if (!l.ok) return JNI_FALSE;
    cam::EnhanceParams p;
    p.denoise = v[0];
    p.clahe = v[1];
    p.sharpen = v[2];
    p.awb = v[3];
    p.gamma = v[4];
    p.vibrance = v[5];
    p.contrast = v[6];
    p.warmth = v[7];
    p.mono = v[8];
    p.dehaze = v[9];
    cam::enhance((uint8_t*) l.px, (int) l.info.width, (int) l.info.height, (int) l.info.stride, p);
    return JNI_TRUE;
}

// Exposure fusion of 2..5 bitmaps (same size, RGBA_8888); the result is written into the first one.
JNIEXPORT jboolean JNICALL Java_com_camera_app_Native_fuse(JNIEnv* env, jclass, jobjectArray frames) {
    if (!frames) return JNI_FALSE;
    const int n = env->GetArrayLength(frames);
    if (n < 2 || n > 5) return JNI_FALSE;
    jobject bmp[5];
    AndroidBitmapInfo info[5];
    void* px[5];
    int locked = 0;
    bool ok = true;
    for (int i = 0; i < n && ok; i++) {
        bmp[i] = env->GetObjectArrayElement(frames, i);
        px[i] = nullptr;
        if (!bmp[i] || AndroidBitmap_getInfo(env, bmp[i], &info[i]) != ANDROID_BITMAP_RESULT_SUCCESS ||
            info[i].format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
            info[i].width != info[0].width || info[i].height != info[0].height ||
            AndroidBitmap_lockPixels(env, bmp[i], &px[i]) != ANDROID_BITMAP_RESULT_SUCCESS || !px[i]) {
            ok = false;
            break;
        }
        locked++;
    }
    bool res = false;
    if (ok) {
        uint8_t* ptr[5];
        int strides[5];
        for (int i = 0; i < n; i++) {
            ptr[i] = (uint8_t*) px[i];
            strides[i] = (int) info[i].stride;
        }
        res = cam::fuse(ptr, strides, n, (int) info[0].width, (int) info[0].height);
    }
    for (int i = 0; i < locked; i++) AndroidBitmap_unlockPixels(env, bmp[i]);
    return res ? JNI_TRUE : JNI_FALSE;
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
