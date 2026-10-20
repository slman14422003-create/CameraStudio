package com.camera.app;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.media.FaceDetector;

final class Portrait {
    private Portrait() {
    }

    private static float[] mask(Bitmap b, int rotation, int mw, int mh) {
        int sw = b.getWidth(), sh = b.getHeight();
        int uw = rotation % 180 == 0 ? sw : sh;
        int uh = rotation % 180 == 0 ? sh : sw;
        float k = 480f / Math.max(uw, uh);
        float[] cx = new float[4], cy = new float[4], ed = new float[4];
        int n = 0;
        try {
            Bitmap small = Bitmap.createScaledBitmap(b, Math.max(2, Math.round(sw * k)), Math.max(2, Math.round(sh * k)), true);
            Matrix r = new Matrix();
            r.postRotate(rotation);
            Bitmap up = Bitmap.createBitmap(small, 0, 0, small.getWidth(), small.getHeight(), r, true);
            if (up != small) small.recycle();
            int ew = up.getWidth() & ~1;
            Bitmap f565 = Bitmap.createBitmap(up, 0, 0, ew, up.getHeight()).copy(Bitmap.Config.RGB_565, true);
            up.recycle();
            FaceDetector fd = new FaceDetector(f565.getWidth(), f565.getHeight(), 4);
            FaceDetector.Face[] faces = new FaceDetector.Face[4];
            int found = fd.findFaces(f565, faces);
            for (int i = 0; i < found && n < 4; i++) {
                if (faces[i] == null || faces[i].confidence() < 0.4f) continue;
                android.graphics.PointF pt = new android.graphics.PointF();
                faces[i].getMidPoint(pt);
                cx[n] = pt.x / f565.getWidth();
                cy[n] = pt.y / f565.getHeight();
                ed[n] = faces[i].eyesDistance() / f565.getWidth();
                n++;
            }
            f565.recycle();
        } catch (Throwable ignored) {
            n = 0;
        }
        float[] out = new float[mw * mh];
        float asp = (float) uh / uw;
        for (int y = 0; y < mh; y++) {
            for (int x = 0; x < mw; x++) {
                float u, v;
                float fx = (x + 0.5f) / mw, fy = (y + 0.5f) / mh;
                switch (rotation) {
                    case 90:
                        u = 1f - fy;
                        v = fx;
                        break;
                    case 180:
                        u = 1f - fx;
                        v = 1f - fy;
                        break;
                    case 270:
                        u = fy;
                        v = 1f - fx;
                        break;
                    default:
                        u = fx;
                        v = fy;
                }
                float best = 0f;
                if (n == 0) {
                    float dx = (u - 0.5f) / 0.46f, dy = (v - 0.48f) / 0.52f * 1f;
                    float rr = (float) Math.sqrt(dx * dx + dy * dy);
                    best = 1f - smooth(0.75f, 1.15f, rr);
                } else {
                    for (int i = 0; i < n; i++) {
                        float d = ed[i];
                        float hx = (u - cx[i]) / (1.7f * d);
                        float hy = (v - (cy[i] + 0.45f * d * 1f / asp)) * asp / (2.3f * d);
                        float rh = (float) Math.sqrt(hx * hx + hy * hy);
                        float bx = (u - cx[i]) / (3.4f * d);
                        float by = (v - (cy[i] + 5.2f * d / asp)) * asp / (3.6f * d);
                        float rb = (float) Math.sqrt(bx * bx + by * by);
                        float mm = Math.max(1f - smooth(0.92f, 1.3f, rh), 1f - smooth(0.9f, 1.35f, rb));
                        if (mm > best) best = mm;
                    }
                }
                out[y * mw + x] = best;
            }
        }
        return out;
    }

    private static float smooth(float a, float b, float x) {
        float t = Math.max(0f, Math.min(1f, (x - a) / (b - a)));
        return t * t * (3f - 2f * t);
    }

    private static void boxH(int[] src, int[] dst, int w, int h, int r) {
        int win = 2 * r + 1;
        for (int y = 0; y < h; y++) {
            int o = y * w;
            long sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = src[o + Math.max(0, Math.min(w - 1, i))];
                sr += (p >> 16) & 255;
                sg += (p >> 8) & 255;
                sb += p & 255;
            }
            for (int x = 0; x < w; x++) {
                dst[o + x] = 0xFF000000 | ((int) (sr / win) << 16) | ((int) (sg / win) << 8) | (int) (sb / win);
                int a = src[o + Math.min(w - 1, x + r + 1)];
                int c = src[o + Math.max(0, x - r)];
                sr += ((a >> 16) & 255) - ((c >> 16) & 255);
                sg += ((a >> 8) & 255) - ((c >> 8) & 255);
                sb += (a & 255) - (c & 255);
            }
        }
    }

    private static void boxV(int[] src, int[] dst, int w, int h, int r) {
        int win = 2 * r + 1;
        for (int x = 0; x < w; x++) {
            long sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = src[Math.max(0, Math.min(h - 1, i)) * w + x];
                sr += (p >> 16) & 255;
                sg += (p >> 8) & 255;
                sb += p & 255;
            }
            for (int y = 0; y < h; y++) {
                dst[y * w + x] = 0xFF000000 | ((int) (sr / win) << 16) | ((int) (sg / win) << 8) | (int) (sb / win);
                int a = src[Math.min(h - 1, y + r + 1) * w + x];
                int c = src[Math.max(0, y - r) * w + x];
                sr += ((a >> 16) & 255) - ((c >> 16) & 255);
                sg += ((a >> 8) & 255) - ((c >> 8) & 255);
                sb += (a & 255) - (c & 255);
            }
        }
    }

    static void apply(Bitmap b, int rotation) {
        try {
            int w = b.getWidth(), h = b.getHeight();
            float k = Math.min(1f, 720f / Math.max(w, h));
            int bw = Math.max(8, Math.round(w * k)), bh = Math.max(8, Math.round(h * k));
            Bitmap sm = Bitmap.createScaledBitmap(b, bw, bh, true);
            int[] a = new int[bw * bh], t = new int[bw * bh];
            sm.getPixels(a, 0, bw, 0, 0, bw, bh);
            sm.recycle();
            int r = Math.max(4, Math.round(Math.max(bw, bh) * 0.018f));
            for (int i = 0; i < 3; i++) {
                boxH(a, t, bw, bh, r);
                boxV(t, a, bw, bh, r);
            }
            float[] m = mask(b, ((rotation % 360) + 360) % 360, bw, bh);
            for (int i = 0; i < 2; i++) {
                float[] s = new float[m.length];
                for (int y = 0; y < bh; y++) {
                    for (int x = 0; x < bw; x++) {
                        float sum = 0f;
                        int c = 0;
                        for (int dy = -3; dy <= 3; dy += 3) {
                            for (int dx = -3; dx <= 3; dx += 3) {
                                int xx = Math.max(0, Math.min(bw - 1, x + dx)), yy = Math.max(0, Math.min(bh - 1, y + dy));
                                sum += m[yy * bw + xx];
                                c++;
                            }
                        }
                        s[y * bw + x] = sum / c;
                    }
                }
                m = s;
            }
            int band = 64;
            int[] row = new int[w * band];
            float sx = (float) bw / w, sy = (float) bh / h;
            for (int y0 = 0; y0 < h; y0 += band) {
                int bh2 = Math.min(band, h - y0);
                b.getPixels(row, 0, w, 0, y0, w, bh2);
                for (int yy = 0; yy < bh2; yy++) {
                    float fy = Math.max(0f, Math.min(bh - 1.001f, (y0 + yy + 0.5f) * sy - 0.5f));
                    int iy = (int) fy;
                    float ty = fy - iy;
                    for (int x = 0; x < w; x++) {
                        float fx = Math.max(0f, Math.min(bw - 1.001f, (x + 0.5f) * sx - 0.5f));
                        int ix = (int) fx;
                        float tx = fx - ix;
                        int i00 = iy * bw + ix, i10 = i00 + 1, i01 = i00 + bw, i11 = i01 + 1;
                        float mk = (m[i00] * (1 - tx) + m[i10] * tx) * (1 - ty) + (m[i01] * (1 - tx) + m[i11] * tx) * ty;
                        if (mk >= 0.999f) continue;
                        float w00 = (1 - tx) * (1 - ty), w10 = tx * (1 - ty), w01 = (1 - tx) * ty, w11 = tx * ty;
                        int p0 = a[i00], p1 = a[i10], p2 = a[i01], p3 = a[i11];
                        float br = w00 * ((p0 >> 16) & 255) + w10 * ((p1 >> 16) & 255) + w01 * ((p2 >> 16) & 255) + w11 * ((p3 >> 16) & 255);
                        float bg = w00 * ((p0 >> 8) & 255) + w10 * ((p1 >> 8) & 255) + w01 * ((p2 >> 8) & 255) + w11 * ((p3 >> 8) & 255);
                        float bb = w00 * (p0 & 255) + w10 * (p1 & 255) + w01 * (p2 & 255) + w11 * (p3 & 255);
                        int o = row[yy * w + x];
                        int rr = Math.round(((o >> 16) & 255) * mk + br * (1 - mk));
                        int gg = Math.round(((o >> 8) & 255) * mk + bg * (1 - mk));
                        int bl = Math.round((o & 255) * mk + bb * (1 - mk));
                        row[yy * w + x] = 0xFF000000 | (rr << 16) | (gg << 8) | bl;
                    }
                }
                b.setPixels(row, 0, w, 0, y0, w, bh2);
            }
        } catch (Throwable ignored) {
        }
    }
}
