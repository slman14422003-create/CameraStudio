package com.camera.app;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Surface;

import androidx.camera.core.CameraEffect;
import androidx.camera.core.SurfaceOutput;
import androidx.camera.core.SurfaceProcessor;
import androidx.camera.core.SurfaceRequest;
import androidx.core.util.Consumer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Live video enhancement on the GPU, applied to the preview AND to the recorded file, so what you see is what you get:
 * edge aware noise reduction, detail-limited sharpening, shadow lift, soft contrast and vibrance.
 * If anything fails (no frames, GL error) {@link #failed()} / {@link #frames()} let the camera fall back to the plain pipeline.
 */
final class VideoFx implements SurfaceProcessor, SurfaceTexture.OnFrameAvailableListener {

    private static final String VERT = ""
            + "attribute vec4 aPos;\n"
            + "attribute vec2 aTex;\n"
            + "uniform mat4 uTex;\n"
            + "varying vec2 vTex;\n"
            + "void main() {\n"
            + "  gl_Position = aPos;\n"
            + "  vTex = (uTex * vec4(aTex, 0.0, 1.0)).xy;\n"
            + "}\n";

    private static final String FRAG = ""
            + "#extension GL_OES_EGL_image_external : require\n"
            + "#ifdef GL_FRAGMENT_PRECISION_HIGH\n"
            + "precision highp float;\n"
            + "#else\n"
            + "precision mediump float;\n"
            + "#endif\n"
            + "varying vec2 vTex;\n"
            + "uniform samplerExternalOES uSampler;\n"
            + "uniform vec2 uPx;\n"
            + "uniform float uStr;\n"
            + "const vec3 K = vec3(0.299, 0.587, 0.114);\n"
            + "void main() {\n"
            + "  vec3 c = texture2D(uSampler, vTex).rgb;\n"
            + "  vec3 n = texture2D(uSampler, vTex + vec2(0.0, -uPx.y)).rgb;\n"
            + "  vec3 s = texture2D(uSampler, vTex + vec2(0.0, uPx.y)).rgb;\n"
            + "  vec3 w = texture2D(uSampler, vTex + vec2(-uPx.x, 0.0)).rgb;\n"
            + "  vec3 e = texture2D(uSampler, vTex + vec2(uPx.x, 0.0)).rgb;\n"
            + "  vec3 avg = (n + s + w + e) * 0.25;\n"
            + "  float lc = dot(c, K);\n"
            + "  float la = dot(avg, K);\n"
            + "  float ln = dot(n, K), ls = dot(s, K), lw = dot(w, K), le = dot(e, K);\n"
            + "  float lo = min(min(min(ln, ls), min(lw, le)), lc);\n"
            + "  float hi = max(max(max(ln, ls), max(lw, le)), lc);\n"
            + "  // noise reduction that keeps edges: flat areas move toward the neighbours, edges stay\n"
            + "  float edge = smoothstep(0.015, 0.11, abs(lc - la));\n"
            + "  vec3 base = mix(mix(c, avg, 0.5 * uStr), c, edge);\n"
            + "  // sharpening with coring (no noise boost) and a limit against halos\n"
            + "  float lb = dot(base, K);\n"
            + "  float detail = lb - la;\n"
            + "  float gain = smoothstep(0.010, 0.050, abs(detail));\n"
            + "  float sharp = lb + detail * (1.25 * uStr) * gain;\n"
            + "  sharp = clamp(sharp, lo - 0.03, hi + 0.03);\n"
            + "  vec3 rgb = base * (sharp / max(lb, 0.002));\n"
            + "  // shadow lift, gentle S curve, vibrance\n"
            + "  float l = clamp(dot(rgb, K), 0.0, 1.0);\n"
            + "  rgb *= 1.0 + 0.38 * uStr * (1.0 - l) * (1.0 - l);\n"
            + "  rgb = clamp(rgb, 0.0, 1.0);\n"
            + "  rgb = mix(rgb, rgb * rgb * (3.0 - 2.0 * rgb), 0.16 * uStr);\n"
            + "  float mx = max(rgb.r, max(rgb.g, rgb.b));\n"
            + "  float mn = min(rgb.r, min(rgb.g, rgb.b));\n"
            + "  float lum = dot(rgb, K);\n"
            + "  rgb = mix(vec3(lum), rgb, 1.0 + 0.30 * uStr * (1.0 - (mx - mn)));\n"
            + "  gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), 1.0);\n"
            + "}\n";

    private static final float[] VERTS = {-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f};
    private static final float[] TEXS = {0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f};

    /** CameraEffect has only protected constructors, so it is subclassed: processes preview and recording. */
    private static final class Fx extends CameraEffect {
        Fx(Executor executor, SurfaceProcessor processor, Consumer<Throwable> errors) {
            super(PREVIEW | VIDEO_CAPTURE, executor, processor, errors);
        }
    }

    private static final class Out {
        final SurfaceOutput output;
        final EGLSurface surface;
        final Size size;
        final float[] matrix = new float[16];

        Out(SurfaceOutput o, EGLSurface s, Size z) {
            output = o;
            surface = s;
            size = z;
        }
    }

    private final HandlerThread thread;
    private final Handler handler;
    private final Executor executor;
    private final CameraEffect effect;
    private final float strength;
    private final List<Out> outs = new ArrayList<>();
    private final float[] texMat = new float[16];

    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLConfig config;
    private EGLSurface pbuffer = EGL14.EGL_NO_SURFACE;
    private int program, texId, aPos, aTex, uTex, uPx, uStr;
    private FloatBuffer vbuf, tbuf;
    private SurfaceTexture surfaceTexture;
    private int inW = 1, inH = 1;
    private volatile int frames;
    private volatile boolean failed, released;

    VideoFx(float strength) {
        this.strength = Math.max(0f, Math.min(1f, strength));
        thread = new HandlerThread("cam-video-fx");
        thread.start();
        handler = new Handler(thread.getLooper());
        executor = r -> handler.post(r);
        effect = new Fx(executor, this, t -> failed = true);
    }

    CameraEffect effect() {
        return effect;
    }

    int frames() {
        return frames;
    }

    boolean failed() {
        return failed;
    }

    // ---------------------------------------------------------------- SurfaceProcessor

    @Override
    public void onInputSurface(SurfaceRequest request) {
        try {
            if (released) {
                request.willNotProvideSurface();
                return;
            }
            ensureGl();
            Size sz = request.getResolution();
            inW = Math.max(1, sz.getWidth());
            inH = Math.max(1, sz.getHeight());
            final SurfaceTexture st = new SurfaceTexture(texId);
            st.setDefaultBufferSize(inW, inH);
            st.setOnFrameAvailableListener(this, handler);
            final Surface surface = new Surface(st);
            surfaceTexture = st;
            request.provideSurface(surface, executor, result -> {
                try {
                    st.setOnFrameAvailableListener(null);
                    surface.release();
                    st.release();
                } catch (Throwable ignored) {
                }
                if (surfaceTexture == st) surfaceTexture = null;
            });
        } catch (Throwable t) {
            failed = true;
            try {
                request.willNotProvideSurface();
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void onOutputSurface(SurfaceOutput output) {
        try {
            if (released) {
                output.close();
                return;
            }
            ensureGl();
            final Surface target = output.getSurface(executor, event -> {
                removeOutput(output);
                output.close();
            });
            EGLSurface es = EGL14.eglCreateWindowSurface(display, config, target, new int[]{EGL14.EGL_NONE}, 0);
            if (es == null || es == EGL14.EGL_NO_SURFACE) throw new IllegalStateException("no window surface");
            outs.add(new Out(output, es, output.getSize()));
        } catch (Throwable t) {
            failed = true;
            try {
                output.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private void removeOutput(SurfaceOutput output) {
        for (int i = outs.size() - 1; i >= 0; i--) {
            Out o = outs.get(i);
            if (o.output == output) {
                try {
                    EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context);
                    EGL14.eglDestroySurface(display, o.surface);
                } catch (Throwable ignored) {
                }
                outs.remove(i);
            }
        }
    }

    // ---------------------------------------------------------------- drawing

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        try {
            if (released || st != surfaceTexture || context == EGL14.EGL_NO_CONTEXT) return;
            EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context);
            st.updateTexImage();
            st.getTransformMatrix(texMat);
            long ts = st.getTimestamp();
            for (Out o : new ArrayList<>(outs)) {
                EGL14.eglMakeCurrent(display, o.surface, o.surface, context);
                o.output.updateTransformMatrix(o.matrix, texMat);
                GLES20.glViewport(0, 0, o.size.getWidth(), o.size.getHeight());
                draw(o.matrix);
                EGLExt.eglPresentationTimeANDROID(display, o.surface, ts);
                EGL14.eglSwapBuffers(display, o.surface);
            }
            if (!outs.isEmpty()) frames++;
        } catch (Throwable t) {
            failed = true;
        }
    }

    private void draw(float[] matrix) {
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
        GLES20.glUniformMatrix4fv(uTex, 1, false, matrix, 0);
        GLES20.glUniform2f(uPx, 1f / inW, 1f / inH);
        GLES20.glUniform1f(uStr, strength);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 8, vbuf);
        GLES20.glEnableVertexAttribArray(aTex);
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 8, tbuf);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisableVertexAttribArray(aTex);
    }

    // ---------------------------------------------------------------- GL setup / teardown

    private void ensureGl() {
        if (context != EGL14.EGL_NO_CONTEXT) return;
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] ver = new int[2];
        if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) throw new IllegalStateException("egl init");
        int[] attrs = {
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                0x3142, 1,   // EGL_RECORDABLE_ANDROID
                EGL14.EGL_NONE};
        EGLConfig[] cfgs = new EGLConfig[1];
        int[] n = new int[1];
        if (!EGL14.eglChooseConfig(display, attrs, 0, cfgs, 0, 1, n, 0) || n[0] < 1) throw new IllegalStateException("egl config");
        config = cfgs[0];
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        if (context == null || context == EGL14.EGL_NO_CONTEXT) throw new IllegalStateException("egl context");
        pbuffer = EGL14.eglCreatePbufferSurface(display, config,
                new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
        if (!EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) throw new IllegalStateException("egl current");

        int vs = compile(GLES20.GL_VERTEX_SHADER, VERT);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAG);
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException("link");
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        aTex = GLES20.glGetAttribLocation(program, "aTex");
        uTex = GLES20.glGetUniformLocation(program, "uTex");
        uPx = GLES20.glGetUniformLocation(program, "uPx");
        uStr = GLES20.glGetUniformLocation(program, "uStr");

        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        texId = tex[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        vbuf = ByteBuffer.allocateDirect(VERTS.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        vbuf.put(VERTS).position(0);
        tbuf = ByteBuffer.allocateDirect(TEXS.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        tbuf.put(TEXS).position(0);
    }

    private static int compile(int type, String src) {
        int sh = GLES20.glCreateShader(type);
        GLES20.glShaderSource(sh, src);
        GLES20.glCompileShader(sh);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException("shader: " + GLES20.glGetShaderInfoLog(sh));
        return sh;
    }

    /** Stops drawing and frees the GL objects (call after the use cases were unbound). */
    void release() {
        released = true;
        handler.post(() -> {
            try {
                if (context != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context);
                    for (Out o : new ArrayList<>(outs)) {
                        try {
                            EGL14.eglDestroySurface(display, o.surface);
                        } catch (Throwable ignored) {
                        }
                    }
                    outs.clear();
                    if (program != 0) GLES20.glDeleteProgram(program);
                    if (texId != 0) GLES20.glDeleteTextures(1, new int[]{texId}, 0);
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    EGL14.eglDestroySurface(display, pbuffer);
                    EGL14.eglDestroyContext(display, context);
                    context = EGL14.EGL_NO_CONTEXT;
                }
            } catch (Throwable ignored) {
            }
            thread.quitSafely();
        });
    }
}
