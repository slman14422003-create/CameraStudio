#include "image_processing.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <vector>
#include <thread>

namespace cam {

namespace {

inline int clampi(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
inline float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

// Runs f(begin, end) over [0, n) on all cores (rows / tiles are independent).
template <typename F>
void parallelFor(int n, F f) {
    int threads = (int) std::thread::hardware_concurrency();
    threads = clampi(threads, 2, 8);
    if (n < threads * 4) threads = 1;
    if (threads == 1) {
        f(0, n);
        return;
    }
    std::vector<std::thread> pool;
    int chunk = (n + threads - 1) / threads;
    for (int t = 0; t < threads; t++) {
        int a = t * chunk, b = std::min(n, a + chunk);
        if (a >= b) break;
        pool.emplace_back([=]() { f(a, b); });
    }
    for (auto& th : pool) th.join();
}

// fast log2 / exp2 (error about 1e-4), used in the per pixel tone code
inline float fastLog2(float x) {
    uint32_t i;
    std::memcpy(&i, &x, 4);
    float e = (float) ((int) ((i >> 23) & 255) - 127);
    i = (i & 0x007FFFFFu) | 0x3F800000u;
    float m;
    std::memcpy(&m, &i, 4);
    float p = ((((0.043928628f * m - 0.40947559f) * m + 1.6101775f) * m - 3.5202188f) * m + 5.0697563f) * m - 2.7941537f;
    return p + e;
}

inline float fastExp2(float x) {
    x = clampf(x, -126.f, 126.f);
    float fl = std::floor(x);
    float f = x - fl;
    float p = 1.0f + f * (0.6931472f + f * (0.2402265f + f * (0.0555041f + f * 0.0096181f)));
    uint32_t i = (uint32_t) ((int) fl + 127) << 23;
    float sc;
    std::memcpy(&sc, &i, 4);
    return p * sc;
}

inline float expNeg(float e) {
    float t = 1.442695f * e;
    t = t > 24.f ? 24.f : t;
    float u = 24.f - t;
    int iu = (int) u;
    float f = u - (float) iu;
    float p = 1.0f + f * (0.6931472f + f * (0.2402265f + f * (0.0555041f + f * 0.0096181f)));
    uint32_t bits = (uint32_t) (iu + 103) << 23;
    float sc;
    std::memcpy(&sc, &bits, 4);
    return p * sc;
}

inline void cubicWeights(float t, float* w4) {
    float t2 = t * t, t3 = t2 * t;
    w4[0] = -0.5f * t3 + t2 - 0.5f * t;
    w4[1] = 1.5f * t3 - 2.5f * t2 + 1.f;
    w4[2] = -1.5f * t3 + 2.f * t2 + 0.5f * t;
    w4[3] = 0.5f * t3 - 0.5f * t2;
}

inline float lumaF(const uint8_t* p) { return 0.299f * p[0] + 0.587f * p[1] + 0.114f * p[2]; }
inline int lumaAt(const uint8_t* rgba, int stride, int x, int y) {
    const uint8_t* p = rgba + (size_t) y * stride + (size_t) x * 4;
    return (77 * p[0] + 150 * p[1] + 29 * p[2]) >> 8;
}

// =============================================================== noise estimate

// Immerkaer's fast estimator: the Laplacian-like mask removes the picture and leaves the noise.
// The median (not the mean) of the response ignores textured areas.
float noiseFromLuma(const uint8_t* rgba, int stride, int w, int h) {
    if (w < 8 || h < 8) return 2.f;
    const int rowStep = std::max(1, h / 400);
    int hist[1024] = {0};
    long count = 0;
    for (int y = 1; y < h - 1; y += rowStep) {
        for (int x = 1; x < w - 1; x += 2) {
            int a = lumaAt(rgba, stride, x - 1, y - 1), b = lumaAt(rgba, stride, x, y - 1), c = lumaAt(rgba, stride, x + 1, y - 1);
            int d = lumaAt(rgba, stride, x - 1, y), e = lumaAt(rgba, stride, x, y), f = lumaAt(rgba, stride, x + 1, y);
            int g = lumaAt(rgba, stride, x - 1, y + 1), hh = lumaAt(rgba, stride, x, y + 1), i = lumaAt(rgba, stride, x + 1, y + 1);
            int v = std::abs(a - 2 * b + c - 2 * d + 4 * e - 2 * f + g - 2 * hh + i);
            hist[std::min(v, 1023)]++;
            count++;
        }
    }
    if (count == 0) return 2.f;
    long acc = 0;
    int med = 0;
    for (int i = 0; i < 1024; i++) {
        acc += hist[i];
        if (acc * 2 >= count) { med = i; break; }
    }
    // response std = 6 * sigma; median of |N(0, s)| = 0.6745 s
    float s = med / (6.f * 0.6745f);
    return clampf(s, 0.6f, 14.f);
}

inline float noiseShape(float luma) { return (2.f - clampf(luma, 0.f, 255.f) * (1.f / 255.f)) * (1.f / 1.5f); }

float noiseFromPatches(const uint8_t* rgba, int stride, int w, int h) {
    constexpr int B = 32, OFF = 3, SN = B + OFF + 2;
    if (w < 3 * B || h < 3 * B) return noiseFromLuma(rgba, stride, w, h);
    const int bw = (w - OFF - 2) / B, bh = (h - OFF - 2) / B;
    const int stepB = std::max(1, (int) std::ceil(std::sqrt((double) bw * bh / 3000.0)));
    std::vector<float> ratio;
    float tile[SN * SN];
    float pdv[2 * (B / 2) * (B / 2)];
    for (int by = 0; by < bh; by += stepB) {
        for (int bx = 0; bx < bw; bx += stepB) {
            const int x0 = bx * B, y0 = by * B;
            float meanL = 0.f;
            for (int y = 0; y < SN; y++) {
                const uint8_t* q = rgba + (size_t) (y0 + y) * stride + (size_t) x0 * 4;
                for (int x = 0; x < SN; x++, q += 4) tile[y * SN + x] = 0.299f * q[0] + 0.587f * q[1] + 0.114f * q[2];
            }
            for (int y = 1; y <= B; y++)
                for (int x = 1; x <= B; x++) meanL += tile[y * SN + x];
            meanL /= (float) (B * B);
            if (meanL < 6.f || meanL > 248.f) continue;
            int n = 0;
            for (int dir = 0; dir < 2; dir++) {
                const int ox = dir == 0 ? OFF : 0, oy = dir == 0 ? 0 : OFF;
                for (int y = 1; y < B; y += 2) {
                    for (int x = 1; x < B; x += 2) {
                        float sum = 0.f;
                        for (int j = -1; j <= 1; j++)
                            for (int i = -1; i <= 1; i++) {
                                float d = tile[(y + j) * SN + (x + i)] - tile[(y + j + oy) * SN + (x + i + ox)];
                                sum += d * d;
                            }
                        pdv[n++] = sum * (1.f / 9.f);
                    }
                }
            }
            const int k = (int) (0.4f * n);
            std::nth_element(pdv, pdv + k, pdv + n);
            float sg = std::sqrt(pdv[k] / (2.f * 0.8174f));
            ratio.push_back(sg / noiseShape(meanL));
        }
    }
    const float lap = noiseFromLuma(rgba, stride, w, h);
    if (ratio.size() < 12) return lap;
    const size_t k = ratio.size() / 4;
    std::nth_element(ratio.begin(), ratio.begin() + k, ratio.end());
    const float hi = 1.25f * std::sqrt(lap * lap + 6.25f);
    return clampf(ratio[k], 0.9f * lap, hi);
}

// =============================================================== global analysis

struct Global {
    float gain[3] = {1.f, 1.f, 1.f};
    float sigma = 2.f;                 // noise level (luma, 0..255)
    std::vector<float> curve;          // tone curve of the smooth base layer, 0..1 -> 0..1
    std::vector<float> base;           // smooth luminance (guided filter), 0..1, at 1/ds size
    int sw = 0, sh = 0, ds = 1;
    float dg = 1.f;                    // gain of the detail layer
    float maxGain = 3.f;               // how much a pixel may be brightened
    float vib = 0.f;                   // vibrance
    bool tone = false;
    bool mono = false;
};

constexpr int kCurveN = 1024;

float curveEval(const Global& g, float b) {
    float f = clampf(b, 0.f, 1.f) * (kCurveN - 1);
    int i = (int) f;
    if (i >= kCurveN - 1) return g.curve[kCurveN - 1];
    float t = f - i;
    return g.curve[i] + (g.curve[i + 1] - g.curve[i]) * t;
}

// Box mean of a float plane (radius r, edges replicated), separable running sum.
void boxMean(const std::vector<float>& src, std::vector<float>& dst, int w, int h, int r) {
    std::vector<float> tmp((size_t) w * h);
    const float inv = 1.f / (2 * r + 1);
    for (int y = 0; y < h; y++) {
        const float* s = &src[(size_t) y * w];
        float* d = &tmp[(size_t) y * w];
        float sum = s[0] * (r + 1);
        for (int i = 1; i <= r; i++) sum += s[std::min(i, w - 1)];
        for (int x = 0; x < w; x++) {
            d[x] = sum * inv;
            sum += s[std::min(x + r + 1, w - 1)] - s[std::max(x - r, 0)];
        }
    }
    dst.resize((size_t) w * h);
    for (int x = 0; x < w; x++) {
        float sum = tmp[x] * (r + 1);
        for (int i = 1; i <= r; i++) sum += tmp[(size_t) std::min(i, h - 1) * w + x];
        for (int y = 0; y < h; y++) {
            dst[(size_t) y * w + x] = sum * inv;
            sum += tmp[(size_t) std::min(y + r + 1, h - 1) * w + x] - tmp[(size_t) std::max(y - r, 0) * w + x];
        }
    }
}

// Edge preserving smoothing (self guided filter, He et al.): keeps the big luminance structure and
// removes the detail, with no halos around strong edges.
void guidedSmooth(const std::vector<float>& I, std::vector<float>& q, int w, int h, int r, float eps) {
    std::vector<float> mean, corr, II((size_t) w * h), a((size_t) w * h), b((size_t) w * h), ma, mb;
    for (size_t i = 0; i < II.size(); i++) II[i] = I[i] * I[i];
    boxMean(I, mean, w, h, r);
    boxMean(II, corr, w, h, r);
    for (size_t i = 0; i < a.size(); i++) {
        float var = std::max(0.f, corr[i] - mean[i] * mean[i]);
        a[i] = var / (var + eps);
        b[i] = mean[i] * (1.f - a[i]);
    }
    boxMean(a, ma, w, h, r);
    boxMean(b, mb, w, h, r);
    q.resize((size_t) w * h);
    for (size_t i = 0; i < q.size(); i++) q[i] = std::max(0.004f, ma[i] * I[i] + mb[i]);
}

Global analyse(const uint8_t* rgba, int w, int h, int stride, const EnhanceParams& p) {
    Global g;
    g.sigma = noiseFromPatches(rgba, stride, w, h);

    // small RGB image (block averages)
    const int maxDim = std::max(w, h);
    g.ds = std::max(1, (int) std::ceil(maxDim / 720.0));
    g.sw = (w + g.ds - 1) / g.ds;
    g.sh = (h + g.ds - 1) / g.ds;
    std::vector<float> sr((size_t) g.sw * g.sh), sg(sr.size()), sb(sr.size());
    const int step = std::max(1, g.ds / 3);
    parallelFor(g.sh, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++) {
            for (int x = 0; x < g.sw; x++) {
                float r = 0, gg = 0, b = 0;
                int c = 0;
                for (int yy = y * g.ds; yy < std::min(h, (y + 1) * g.ds); yy += step) {
                    const uint8_t* q = rgba + (size_t) yy * stride;
                    for (int xx = x * g.ds; xx < std::min(w, (x + 1) * g.ds); xx += step) {
                        r += q[xx * 4];
                        gg += q[xx * 4 + 1];
                        b += q[xx * 4 + 2];
                        c++;
                    }
                }
                size_t i = (size_t) y * g.sw + x;
                float inv = c ? 1.f / c : 0.f;
                sr[i] = r * inv;
                sg[i] = gg * inv;
                sb[i] = b * inv;
            }
        }
    });

    // white balance: average of the nearly neutral, well exposed pixels
    if (p.awb > 0.f) {
        double wr = 0, wg = 0, wb = 0, wt = 0;
        for (size_t i = 0; i < sr.size(); i++) {
            float r = sr[i], gg = sg[i], b = sb[i];
            float l = 0.299f * r + 0.587f * gg + 0.114f * b;
            if (l < 22.f || l > 235.f) continue;
            float chroma = std::max(std::fabs(r - gg), std::max(std::fabs(gg - b), std::fabs(r - b)));
            float wgt = std::exp(-(chroma * chroma) / (2.f * 38.f * 38.f));
            wr += wgt * r; wg += wgt * gg; wb += wgt * b; wt += wgt;
        }
        if (wt > sr.size() * 0.02 && wr > 1 && wg > 1 && wb > 1) {
            double m = (wr + wg + wb) / 3.0;
            float gains[3] = {(float) (m / wr), (float) (m / wg), (float) (m / wb)};
            for (int c = 0; c < 3; c++) {
                float v = clampf(gains[c], 0.86f, 1.16f);
                g.gain[c] = 1.f + (v - 1.f) * p.awb;
            }
        }
    }

    // warm / cool
    if (p.warmth != 0.f) {
        g.gain[0] *= 1.f + 0.08f * p.warmth;
        g.gain[2] *= 1.f - 0.08f * p.warmth;
    }

    // tone curve from the luminance distribution (after the white balance gains)
    std::vector<float> Ys(sr.size());
    int hist[256] = {0};
    for (size_t i = 0; i < sr.size(); i++) {
        float l = (0.299f * std::min(255.f, sr[i] * g.gain[0]) + 0.587f * std::min(255.f, sg[i] * g.gain[1]) +
                   0.114f * std::min(255.f, sb[i] * g.gain[2])) / 255.f;
        Ys[i] = std::max(0.004f, l);
        hist[clampi((int) (l * 255.f), 0, 255)]++;
    }
    long cum = 0, total = (long) Ys.size();
    float p50 = 0.4f, p98 = 0.95f, p02 = 0.f;
    bool got02 = false, got50 = false;
    for (int i = 0; i < 256; i++) {
        cum += hist[i];
        if (!got02 && cum * 100 >= total * 2) { p02 = i / 255.f; got02 = true; }
        if (!got50 && cum * 2 >= total) { p50 = i / 255.f; got50 = true; }
        if (cum * 100 >= total * 98) { p98 = i / 255.f; break; }
    }
    p50 = clampf(p50, 0.03f, 0.9f);

    float shadowShare = 0.f, highShare = 0.f;
    for (int i = 0; i < 256; i++) {
        if (i < 46) shadowShare += hist[i];
        if (i > 240) highShare += hist[i];
    }
    shadowShare /= std::max(1.f, (float) total);
    highShare /= std::max(1.f, (float) total);
    const float needShadow = clampf((shadowShare - 0.06f) / 0.34f, 0.f, 1.f);
    const float needDark = clampf((0.38f - p50) / 0.22f, 0.f, 1.f);
    const float need = std::max(0.75f * needShadow, needDark);
    const float needHigh = clampf((highShare - 0.003f) / 0.04f, 0.f, 1.f);

    const float T = p.clahe, G = p.gamma;
    float e = std::log(0.40f) / std::log(p50);
    e = clampf(e, 0.62f, 1.0f);
    const float expo = 1.f + (e - 1.f) * G * needDark;
    const float ls = (0.50f * T + 0.20f * G) * (0.10f + 0.90f * need);
    const float hc = 0.55f * T * (0.20f + 0.80f * needHigh);
    const float cs = clampf(0.10f + 0.10f * T + 0.22f * p.contrast + 0.05f * (1.f - need), -0.05f, 0.5f);
    const float bp = clampf(p.dehaze * std::min(p02, 0.22f) * 0.9f, 0.f, 0.2f);
    g.tone = (T > 0.f || G > 0.f || p.dehaze > 0.f || p.contrast != 0.f);
    g.mono = p.mono > 0.5f;
    g.curve.resize(kCurveN);
    float run = 0.f;
    for (int i = 0; i < kCurveN; i++) {
        float b = i / (float) (kCurveN - 1);
        float y = std::pow(b, expo);
        y += ls * 2.2f * y * (1.f - y) * (1.f - y);
        y = std::min(1.f, y);
        const float k = 0.62f;
        if (y > k) {
            float t = (y - k) / (1.f - k);
            y = k + (1.f - k) * t / (1.f + hc * (1.f - t));
        }
        float sc = y * y * (3.f - 2.f * y);
        y = y + cs * (sc - y);
        if (bp > 0.f) y = (y - bp) / (1.f - bp);
        y = clampf(y, 0.f, 1.f);
        run = std::max(run, y);
        g.curve[i] = run;
    }
    g.dg = clampf(1.f + 0.18f * T + 0.10f * p.sharpen + 0.12f * p.dehaze, 1.f, 1.45f);
    g.maxGain = 2.2f + 2.2f * G + 1.2f * T;
    g.vib = clampf(0.16f + 0.22f * T + 0.10f * p.awb + p.vibrance, -0.4f, 1.0f);

    // smooth base layer of the luminance
    if (g.tone) {
        int r = std::max(3, g.sw / 36);
        guidedSmooth(Ys, g.base, g.sw, g.sh, r, 0.012f);
    }
    return g;
}

inline float sampleBase(const Global& g, int x, int y) {
    float fx = (x + 0.5f) / g.ds - 0.5f, fy = (y + 0.5f) / g.ds - 0.5f;
    fx = clampf(fx, 0.f, g.sw - 1.f);
    fy = clampf(fy, 0.f, g.sh - 1.f);
    int x0 = (int) fx, y0 = (int) fy;
    int x1 = std::min(x0 + 1, g.sw - 1), y1 = std::min(y0 + 1, g.sh - 1);
    float wx = fx - x0, wy = fy - y0;
    const float* b = g.base.data();
    float a = b[(size_t) y0 * g.sw + x0], c = b[(size_t) y0 * g.sw + x1];
    float d = b[(size_t) y1 * g.sw + x0], e = b[(size_t) y1 * g.sw + x1];
    float top = a + (c - a) * wx, bot = d + (e - d) * wx;
    return top + (bot - top) * wy;
}

// =============================================================== band filters

struct Gauss {
    std::vector<float> k;
    int r = 1;
};

Gauss makeGauss(float sigma) {
    Gauss g;
    g.r = std::max(1, (int) std::ceil(3.f * sigma));
    g.k.resize(2 * g.r + 1);
    float sum = 0;
    for (int i = -g.r; i <= g.r; i++) {
        g.k[i + g.r] = std::exp(-(i * i) / (2.f * sigma * sigma));
        sum += g.k[i + g.r];
    }
    for (auto& v : g.k) v /= sum;
    return g;
}

// dst = gauss(src); tmp is scratch. All planes are rows*w floats.
void gaussBlur(const float* src, float* dst, float* tmp, int w, int rows, const Gauss& g) {
    const int r = g.r;
    parallelFor(rows, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++) {
            const float* s = src + (size_t) y * w;
            float* d = tmp + (size_t) y * w;
            for (int x = 0; x < w; x++) {
                float acc = 0;
                if (x >= r && x < w - r) {
                    for (int i = -r; i <= r; i++) acc += g.k[i + r] * s[x + i];
                } else {
                    for (int i = -r; i <= r; i++) acc += g.k[i + r] * s[clampi(x + i, 0, w - 1)];
                }
                d[x] = acc;
            }
        }
    });
    parallelFor(rows, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++) {
            float* d = dst + (size_t) y * w;
            for (int x = 0; x < w; x++) d[x] = 0.f;
            for (int i = -r; i <= r; i++) {
                const float* s = tmp + (size_t) clampi(y + i, 0, rows - 1) * w;
                float k = g.k[i + r];
                for (int x = 0; x < w; x++) d[x] += k * s[x];
            }
        }
    });
}

constexpr int kTileW = 192;
constexpr int kTileH = 32;
constexpr float kPostGainMin = 1.08f;
constexpr float kPostGainSpan = 0.4f;

struct NlmScratch {
    std::vector<float> P, Q, D, Hh, num, den, inv, thr;
    NlmScratch() {
        const int pw = kTileW + 8, ph = kTileH + 8;
        P.resize((size_t) pw * ph);
        Q.resize((size_t) pw * ph);
        D.resize((size_t) (kTileW + 2) * (kTileH + 2));
        Hh.resize((size_t) kTileW * (kTileH + 2));
        num.resize((size_t) kTileW * kTileH);
        den.resize(num.size());
        inv.resize(num.size());
        thr.resize(num.size());
    }
};

void lumaNlm(const float* src, float* dst, const float* gain, int w, int rows, float noise, float h, int R, float darkK, bool impulse) {
    const int M = R + 1;
    const float ssp = R <= 2 ? 2.0f : 2.5f;
    float spat[7][7];
    for (int dy = -R; dy <= R; dy++)
        for (int dx = -R; dx <= R; dx++) spat[dy + R][dx + R] = std::exp(-(dx * dx + dy * dy) / (2.f * ssp * ssp));
    const float darkNorm = 1.f / (1.f + 0.5f * darkK);
    const float h2 = h * h;
    const float spikeT = std::max(10.f, 6.f * noise);
    parallelFor(rows, [&](int ya, int yb) {
        NlmScratch S;
        for (int y0 = ya; y0 < yb; y0 += kTileH) {
            const int y1 = std::min(yb, y0 + kTileH), th = y1 - y0;
            for (int x0 = 0; x0 < w; x0 += kTileW) {
                const int x1 = std::min(w, x0 + kTileW), tw = x1 - x0;
                const int PW = tw + 2 * M, PH = th + 2 * M;
                if (gain) {
                    float gm = 0.f;
                    for (int r = 0; r < th; r++) {
                        const float* gp = gain + (size_t) (y0 + r) * w + x0;
                        for (int x = 0; x < tw; x++) gm = std::max(gm, gp[x]);
                    }
                    if (gm < kPostGainMin) {
                        for (int r = 0; r < th; r++)
                            std::memcpy(dst + (size_t) (y0 + r) * w + x0, src + (size_t) (y0 + r) * w + x0, (size_t) tw * sizeof(float));
                        continue;
                    }
                }
                for (int i = 0; i < PH; i++) {
                    const float* s = src + (size_t) clampi(y0 - M + i, 0, rows - 1) * w;
                    float* p = &S.P[(size_t) i * PW];
                    for (int j = 0; j < PW; j++) p[j] = s[clampi(x0 - M + j, 0, w - 1)];
                }
                const float* Q = S.P.data();
                if (impulse) {
                    std::memcpy(S.Q.data(), S.P.data(), (size_t) PW * PH * sizeof(float));
                    for (int i = 1; i < PH - 1; i++) {
                        const float* u = &S.P[(size_t) (i - 1) * PW];
                        const float* m = &S.P[(size_t) i * PW];
                        const float* d = &S.P[(size_t) (i + 1) * PW];
                        float* o = &S.Q[(size_t) i * PW];
                        for (int j = 1; j < PW - 1; j++) {
                            float mx = std::max(std::max(std::max(u[j - 1], u[j]), std::max(u[j + 1], m[j - 1])),
                                                std::max(std::max(m[j + 1], d[j - 1]), std::max(d[j], d[j + 1])));
                            float mn = std::min(std::min(std::min(u[j - 1], u[j]), std::min(u[j + 1], m[j - 1])),
                                                std::min(std::min(m[j + 1], d[j - 1]), std::min(d[j], d[j + 1])));
                            float c = m[j];
                            if (c > mx + spikeT || c < mn - spikeT)
                                o[j] = (u[j - 1] + u[j] + u[j + 1] + m[j - 1] + m[j + 1] + d[j - 1] + d[j] + d[j + 1]) * 0.125f;
                        }
                    }
                    Q = S.Q.data();
                }
                for (int r = 0; r < th; r++) {
                    const float* c0 = &Q[(size_t) (M + r) * PW + M];
                    const float* cu = c0 - PW;
                    const float* cd = c0 + PW;
                    const float* gp = gain ? gain + (size_t) (y0 + r) * w + x0 : nullptr;
                    float* nm = &S.num[(size_t) r * tw];
                    float* dn = &S.den[(size_t) r * tw];
                    float* iv = &S.inv[(size_t) r * tw];
                    float* tr = &S.thr[(size_t) r * tw];
                    for (int x = 0; x < tw; x++) {
                        float c = c0[x];
                        float mm = (c + cu[x] + cd[x] + c0[x - 1] + c0[x + 1]) * 0.2f;
                        float sl;
                        if (gp) {
                            float gg = std::max(1.f, gp[x]);
                            sl = noise * gg * noiseShape(mm / gg);
                        } else {
                            sl = noise * (1.f + darkK * (1.f - mm * (1.f / 255.f))) * darkNorm;
                        }
                        sl = std::max(sl, 0.35f);
                        tr[x] = 2.f * sl * sl;
                        iv[x] = 1.f / (h2 * sl * sl);
                        nm[x] = c;
                        dn[x] = 1.f;
                    }
                }
                for (int dy = -R; dy <= R; dy++) {
                    for (int dx = -R; dx <= R; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        const float sw = spat[dy + R][dx + R];
                        for (int rp = 0; rp < th + 2; rp++) {
                            const float* a = &Q[(size_t) (M - 1 + rp) * PW + (M - 1)];
                            const float* b = &Q[(size_t) (M - 1 + rp + dy) * PW + (M - 1 + dx)];
                            float* d = &S.D[(size_t) rp * (tw + 2)];
                            for (int xp = 0; xp < tw + 2; xp++) {
                                float t = a[xp] - b[xp];
                                d[xp] = t * t;
                            }
                            const float* dd = d;
                            float* hh = &S.Hh[(size_t) rp * tw];
                            for (int x = 0; x < tw; x++) hh[x] = dd[x] + dd[x + 1] + dd[x + 2];
                        }
                        for (int r = 0; r < th; r++) {
                            const float* h0 = &S.Hh[(size_t) r * tw];
                            const float* h1 = h0 + tw;
                            const float* h2p = h1 + tw;
                            const float* nb = &Q[(size_t) (M + r + dy) * PW + M + dx];
                            const float* tr = &S.thr[(size_t) r * tw];
                            const float* iv = &S.inv[(size_t) r * tw];
                            float* nm = &S.num[(size_t) r * tw];
                            float* dn = &S.den[(size_t) r * tw];
                            for (int x = 0; x < tw; x++) {
                                float pd = (h0[x] + h1[x] + h2p[x]) * (1.f / 9.f);
                                float e = pd - tr[x];
                                e = e > 0.f ? e * iv[x] : 0.f;
                                float wg = sw * expNeg(e);
                                nm[x] += wg * nb[x];
                                dn[x] += wg;
                            }
                        }
                    }
                }
                for (int r = 0; r < th; r++) {
                    const float* nm = &S.num[(size_t) r * tw];
                    const float* dn = &S.den[(size_t) r * tw];
                    float* o = dst + (size_t) (y0 + r) * w + x0;
                    if (gain) {
                        const float* gp = gain + (size_t) (y0 + r) * w + x0;
                        const float* c0 = &Q[(size_t) (M + r) * PW + M];
                        for (int x = 0; x < tw; x++) {
                            float f = clampf((gp[x] - kPostGainMin) * (1.f / kPostGainSpan), 0.f, 1.f);
                            o[x] = c0[x] + f * (nm[x] / dn[x] - c0[x]);
                        }
                    } else {
                        for (int x = 0; x < tw; x++) o[x] = nm[x] / dn[x];
                    }
                }
            }
        }
    });
}

void lumaDenoise(const float* src, float* dst, int w, int rows, float sigma, float strength) {
    if (strength <= 0.01f) {
        std::memcpy(dst, src, (size_t) w * rows * sizeof(float));
        return;
    }
    lumaNlm(src, dst, nullptr, w, rows, sigma, 0.55f + 1.5f * strength, 2, 1.0f, true);
}

void lumaDenoisePost(const float* src, float* dst, const float* gain, int w, int rows, float noise, float strength) {
    lumaNlm(src, dst, gain, w, rows, noise, 0.55f + 1.5f * strength, 2, 0.f, false);
}

void chromaDenoise(float* cb, float* cr, const float* yGuide, int hw, int hr, float sigma, float strength) {
    const float sY = 5.f + 1.8f * sigma;
    const float sC = 1.2f + 4.f * strength + 0.7f * sigma;
    const float invY = 1.f / (2.f * sY * sY);
    float spat[5][5];
    for (int dy = -2; dy <= 2; dy++)
        for (int dx = -2; dx <= 2; dx++) spat[dy + 2][dx + 2] = std::exp(-(dx * dx + dy * dy) / (2.f * 1.3f * 1.3f));
    std::vector<float> ocb((size_t) hw * hr), ocr((size_t) hw * hr);
    const int passes = strength > 0.4f ? 2 : 1;
    for (int pass = 0; pass < passes; pass++) {
        parallelFor(hr, [&](int y0, int y1) {
            for (int y = y0; y < y1; y++) {
                for (int x = 0; x < hw; x++) {
                    size_t ci = (size_t) y * hw + x;
                    float gy = yGuide[ci], pb = cb[ci], pr = cr[ci];
                    float sc = sC * (0.6f + 0.9f * (1.f - clampf(gy, 0.f, 255.f) * (1.f / 255.f)));
                    float invC = 1.f / (2.f * sc * sc);
                    float nb = 0, nr = 0, den = 0;
                    for (int dy = -2; dy <= 2; dy++) {
                        int yy = clampi(y + dy, 0, hr - 1);
                        for (int dx = -2; dx <= 2; dx++) {
                            size_t j = (size_t) yy * hw + clampi(x + dx, 0, hw - 1);
                            float dY = yGuide[j] - gy;
                            float db = cb[j] - pb, dr = cr[j] - pr;
                            float wt = spat[dy + 2][dx + 2] * expNeg(dY * dY * invY + (db * db + dr * dr) * invC);
                            nb += wt * cb[j];
                            nr += wt * cr[j];
                            den += wt;
                        }
                    }
                    ocb[ci] = den > 0 ? nb / den : pb;
                    ocr[ci] = den > 0 ? nr / den : pr;
                }
            }
        });
        std::memcpy(cb, ocb.data(), ocb.size() * sizeof(float));
        std::memcpy(cr, ocr.data(), ocr.size() * sizeof(float));
    }
}

inline float bilinear(const float* plane, int pw, int ph, float fx, float fy) {
    fx = clampf(fx, 0.f, pw - 1.f);
    fy = clampf(fy, 0.f, ph - 1.f);
    int x0 = (int) fx, y0 = (int) fy;
    int x1 = std::min(x0 + 1, pw - 1), y1 = std::min(y0 + 1, ph - 1);
    float wx = fx - x0, wy = fy - y0;
    float a = plane[(size_t) y0 * pw + x0], b = plane[(size_t) y0 * pw + x1];
    float c = plane[(size_t) y1 * pw + x0], d = plane[(size_t) y1 * pw + x1];
    float top = a + (b - a) * wx, bot = c + (d - c) * wx;
    return top + (bot - top) * wy;
}

inline float ditherNoise(uint32_t n) {
    n ^= n << 13; n *= 0x9E3779B1u; n ^= n >> 15; n *= 0x85EBCA77u; n ^= n >> 13;
    float a = (n & 0xFFFF) / 65535.f, b = ((n >> 16) & 0xFFFF) / 65535.f;
    return a + b - 1.f;   // triangular, -1..1
}

}  // namespace

float estimateNoise(const uint8_t* rgba, int w, int h, int stride) { return noiseFromLuma(rgba, stride, w, h); }

void enhance(uint8_t* rgba, int w, int h, int stride, const EnhanceParams& in) {
    if (!rgba || w < 16 || h < 16) return;
    EnhanceParams p = in;
    p.denoise = clampf(p.denoise, 0.f, 1.f);
    p.clahe = clampf(p.clahe, 0.f, 1.f);
    p.sharpen = clampf(p.sharpen, 0.f, 1.f);
    p.awb = clampf(p.awb, 0.f, 1.f);
    p.gamma = clampf(p.gamma, 0.f, 1.f);
    p.vibrance = clampf(p.vibrance, -0.4f, 1.f);
    p.contrast = clampf(p.contrast, -0.3f, 1.f);
    p.warmth = clampf(p.warmth, -1.f, 1.f);
    p.dehaze = clampf(p.dehaze, 0.f, 1.f);
    if (p.denoise <= 0.f && p.clahe <= 0.f && p.sharpen <= 0.f && p.awb <= 0.f && p.gamma <= 0.f && p.vibrance == 0.f &&
        p.contrast == 0.f && p.warmth == 0.f && p.mono <= 0.5f && p.dehaze <= 0.f) return;

    const Global g = analyse(rgba, w, h, stride, p);
    // noisier pictures need more filtering than the preset alone asks for
    const float denEff = clampf(p.denoise + (p.denoise > 0.f ? 0.05f * std::max(0.f, g.sigma - 2.5f) : 0.f), 0.f, 1.f);

    // sharpening scales follow the resolution (a 50 MP picture needs wider radii than a 12 MP one)
    const float s = std::max(w, h) / 4000.f;
    const float sig1 = std::max(0.7f, 0.9f * s);
    const float sig2 = std::max(2.2f, 3.0f * s);
    const Gauss g1 = makeGauss(sig1), g2 = makeGauss(sig2);
    const int H = (int) std::ceil(3.f * sig2) + 10;
    const int BH = 128;
    const int maxR = BH + 2 * H;
    const size_t plane = (size_t) maxR * w;
    const int hwMax = (w + 1) / 2, hrMax = (maxR + 1) / 2;

    std::vector<float> Y0(plane), Yd(plane), Tm(plane), G1(plane), G2(plane), Cb(plane), Cr(plane), Gn(plane);
    std::vector<float> hcb((size_t) hwMax * hrMax), hcr(hcb.size()), hg(hcb.size());
    std::vector<uint8_t> carry((size_t) H * stride), carryNext((size_t) H * stride);

    const float nzDen = g.sigma * (1.f - 0.55f * p.denoise);       // noise left after the filter
    const float thr1 = std::max(0.7f, 0.95f * nzDen);
    const float thr2 = std::max(1.0f, 1.6f * nzDen);
    const float a1 = 0.55f + 1.35f * p.sharpen;
    const float a2 = 0.12f + 0.50f * p.sharpen * (0.5f + p.clahe);

    for (int y0 = 0; y0 < h; y0 += BH) {
        const int y1 = std::min(h, y0 + BH);
        const int R = (y1 - y0) + 2 * H;
        const int keep0 = H, keep1 = H + (y1 - y0);

        // original rows that the next band will need as its upper halo
        if (y1 < h) {
            for (int i = 0; i < H; i++) {
                int yy = y1 - H + i;
                std::memcpy(&carryNext[(size_t) i * stride], rgba + (size_t) yy * stride, (size_t) w * 4);
            }
        }

        auto rowPtr = [&](int r) -> const uint8_t* {
            int yy = y0 - H + r;
            if (yy < y0 && yy >= 0 && y0 > 0) return &carry[(size_t) (yy - (y0 - H)) * stride];
            return rgba + (size_t) clampi(yy, 0, h - 1) * stride;
        };

        // 1. RGB -> YCbCr with the white balance gains
        parallelFor(R, [&](int a, int b) {
            for (int r = a; r < b; r++) {
                const uint8_t* q = rowPtr(r);
                float* y = &Y0[(size_t) r * w];
                float* cb = &Cb[(size_t) r * w];
                float* cr = &Cr[(size_t) r * w];
                for (int x = 0; x < w; x++, q += 4) {
                    float R_ = std::min(255.f, q[0] * g.gain[0]);
                    float G_ = std::min(255.f, q[1] * g.gain[1]);
                    float B_ = std::min(255.f, q[2] * g.gain[2]);
                    y[x] = 0.299f * R_ + 0.587f * G_ + 0.114f * B_;
                    cb[x] = -0.168736f * R_ - 0.331264f * G_ + 0.5f * B_;
                    cr[x] = 0.5f * R_ - 0.418688f * G_ - 0.081312f * B_;
                }
            }
        });

        // 2. luma denoise
        lumaDenoise(Y0.data(), Yd.data(), w, R, g.sigma, denEff);

        // 3. chroma denoise at half resolution (guided by the denoised luma)
        const int hw = (w + 1) / 2, hr = (R + 1) / 2;
        parallelFor(hr, [&](int a, int b) {
            for (int y = a; y < b; y++) {
                for (int x = 0; x < hw; x++) {
                    int x0 = std::min(2 * x, w - 1), x1 = std::min(2 * x + 1, w - 1);
                    int ya = std::min(2 * y, R - 1), yb = std::min(2 * y + 1, R - 1);
                    size_t i00 = (size_t) ya * w + x0, i01 = (size_t) ya * w + x1;
                    size_t i10 = (size_t) yb * w + x0, i11 = (size_t) yb * w + x1;
                    size_t o = (size_t) y * hw + x;
                    hcb[o] = 0.25f * (Cb[i00] + Cb[i01] + Cb[i10] + Cb[i11]);
                    hcr[o] = 0.25f * (Cr[i00] + Cr[i01] + Cr[i10] + Cr[i11]);
                    hg[o] = 0.25f * (Yd[i00] + Yd[i01] + Yd[i10] + Yd[i11]);
                }
            }
        });
        chromaDenoise(hcb.data(), hcr.data(), hg.data(), hw, hr, g.sigma, std::max(denEff, 0.25f));

        // 4. local tone mapping + detail gain + chroma (upsampled) with vibrance
        parallelFor(R, [&](int a, int b) {
            for (int r = a; r < b; r++) {
                int gy = clampi(y0 - H + r, 0, h - 1);
                const float* yd = &Yd[(size_t) r * w];
                float* yo = &Y0[(size_t) r * w];
                float* cbo = &Cb[(size_t) r * w];
                float* cro = &Cr[(size_t) r * w];
                float* gno = &Gn[(size_t) r * w];
                float fy = (r + 0.5f) / 2.f - 0.5f;
                for (int x = 0; x < w; x++) {
                    float cb = bilinear(hcb.data(), hw, hr, (x + 0.5f) / 2.f - 0.5f, fy);
                    float cr = bilinear(hcr.data(), hw, hr, (x + 0.5f) / 2.f - 0.5f, fy);
                    float Yn = std::max(0.004f, yd[x] / 255.f);
                    float Yt = Yn;
                    if (g.tone) {
                        float base = std::max(0.004f, sampleBase(g, x, gy));
                        float fb = std::min(curveEval(g, base), base * g.maxGain);
                        float ratio = clampf(Yn / base, 0.25f, 4.f);
                        Yt = fb * fastExp2(g.dg * fastLog2(ratio));
                        // very bright pixels must not be pushed over the top
                        Yt = std::min(Yt, 1.f);
                    }
                    // keep the colour strength when the luminance is lifted
                    float lift = Yt / Yn;
                    float sat = clampf(std::pow(lift, 0.8f), 0.9f, 1.55f);
                    float mag = std::sqrt(cb * cb + cr * cr) / 90.f;
                    mag = clampf(mag, 0.f, 1.f);
                    float vib = 1.f + g.vib * (1.f - mag) * (1.f - mag);
                    if (cb < -4.f && cb > -52.f && cr > 6.f && cr < 52.f) vib = 1.f + (vib - 1.f) * 0.45f;   // skin
                    float cs = g.mono ? 0.f : sat * vib;
                    yo[x] = Yt * 255.f;
                    gno[x] = clampf(lift, 0.5f, 8.f);
                    cbo[x] = cb * cs;
                    cro[x] = cr * cs;
                }
            }
        });

        // 4b. noise that the tone mapping amplified
        if (g.tone && p.denoise > 0.f) {
            lumaDenoisePost(Y0.data(), Yd.data(), Gn.data(), w, R, g.sigma * (1.f - 0.55f * denEff), denEff);
            Y0.swap(Yd);
        }

        // 5. two scale sharpening (fine detail + local clarity), noise coring, halo limit
        gaussBlur(Y0.data(), G1.data(), Tm.data(), w, R, g1);
        gaussBlur(Y0.data(), G2.data(), Tm.data(), w, R, g2);
        parallelFor(keep1 - keep0, [&](int a, int b) {
            for (int rr = a; rr < b; rr++) {
                int r = keep0 + rr;
                const float* y = &Y0[(size_t) r * w];
                const float* ym = &Y0[(size_t) (r - 1) * w];
                const float* yp = &Y0[(size_t) (r + 1) * w];
                const float* b1 = &G1[(size_t) r * w];
                const float* b2 = &G2[(size_t) r * w];
                const float* gn = &Gn[(size_t) r * w];
                float* out = &Yd[(size_t) r * w];
                for (int x = 0; x < w; x++) {
                    float c = y[x];
                    float d1 = c - b1[x];
                    float d2 = b1[x] - b2[x];
                    float dark = std::max(1.f, gn[x]) * (1.f + 0.4f * (1.f - c / 255.f));
                    float t1 = thr1 * dark, t2 = thr2 * dark;
                    float w1 = d1 * d1 / (d1 * d1 + t1 * t1 + 1e-6f);
                    float w2 = d2 * d2 / (d2 * d2 + t2 * t2 + 1e-6f);
                    float v = c + a1 * d1 * w1 + a2 * d2 * w2;
                    int xl = std::max(0, x - 1), xr = std::min(w - 1, x + 1);
                    float lo = std::min(std::min(std::min(ym[xl], ym[x]), std::min(ym[xr], y[xl])),
                                        std::min(std::min(y[x], y[xr]), std::min(yp[xl], std::min(yp[x], yp[xr]))));
                    float hi = std::max(std::max(std::max(ym[xl], ym[x]), std::max(ym[xr], y[xl])),
                                        std::max(std::max(y[x], y[xr]), std::max(yp[xl], std::max(yp[x], yp[xr]))));
                    const float m = 5.f;
                    v = clampf(v, lo - m, hi + m);
                    out[x] = v;
                }
            }
        });

        // 6. back to RGB with dithering (avoids banding after the shadow lift)
        const float dith = g.tone ? 0.6f : 0.3f;
        parallelFor(keep1 - keep0, [&](int a, int b) {
            for (int rr = a; rr < b; rr++) {
                int r = keep0 + rr;
                int gy = y0 + rr;
                const float* y = &Yd[(size_t) r * w];
                const float* cb = &Cb[(size_t) r * w];
                const float* cr = &Cr[(size_t) r * w];
                uint8_t* o = rgba + (size_t) gy * stride;
                for (int x = 0; x < w; x++, o += 4) {
                    float Y = clampf(y[x], 0.f, 255.f);
                    float R_ = Y + 1.402f * cr[x];
                    float G_ = Y - 0.344136f * cb[x] - 0.714136f * cr[x];
                    float B_ = Y + 1.772f * cb[x];
                    float dn = dith * ditherNoise((uint32_t) (x * 7919 + gy * 104729));
                    o[0] = (uint8_t) clampi((int) (R_ + 0.5f + dn), 0, 255);
                    o[1] = (uint8_t) clampi((int) (G_ + 0.5f + dn), 0, 255);
                    o[2] = (uint8_t) clampi((int) (B_ + 0.5f + dn), 0, 255);
                }
            }
        });

        if (y1 < h) std::swap(carry, carryNext);
    }
}

// =============================================================== multi-frame merge

namespace {

void smallLuma(const uint8_t* rgba, int stride, int w, int h, int sw, int sh, uint8_t* out) {
    parallelFor(sh, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++) {
            for (int x = 0; x < sw; x++) {
                int sum = 0, c = 0;
                for (int dy = 0; dy < 4; dy++) {
                    int yy = y * 4 + dy;
                    if (yy >= h) break;
                    for (int dx = 0; dx < 4; dx++) {
                        int xx = x * 4 + dx;
                        if (xx >= w) break;
                        sum += lumaAt(rgba, stride, xx, yy);
                        c++;
                    }
                }
                out[(size_t) y * sw + x] = (uint8_t) (c ? sum / c : 0);
            }
        }
    });
}

}  // namespace

Merger::Merger(int w, int h) : w_(w), h_(h), sw_((w + 3) / 4), sh_((h + 3) / 4) {
    tile_ = (long) w * h > 20000000L ? 128 : 64;
    acc_.assign((size_t) w * h * 3, 0);
    cnt_.assign((size_t) w * h, 0);
}

bool Merger::add(const uint8_t* rgba, int stride) {
    if (n_ >= 250) return false;
    int dx = 0, dy = 0;
    std::vector<uint8_t> small((size_t) sw_ * sh_);
    smallLuma(rgba, stride, w_, h_, sw_, sh_, small.data());

    const int tile = tile_;
    const int tilesX = (w_ + tile - 1) / tile, tilesY = (h_ + tile - 1) / tile;
    std::vector<float> offX((size_t) tilesX * tilesY, 0.f), offY((size_t) tilesX * tilesY, 0.f);

    if (n_ == 0) {
        refSmall_ = small;
        refY_.resize((size_t) w_ * h_);
        parallelFor(h_, [&](int y0, int y1) {
            for (int y = y0; y < y1; y++)
                for (int x = 0; x < w_; x++) refY_[(size_t) y * w_ + x] = (uint8_t) lumaAt(rgba, stride, x, y);
        });
        sigma_ = noiseFromPatches(rgba, stride, w_, h_);
    } else {
        // global shift: coarse search at 1/4 size: +-12 px => +-48 px on the full image
        const int R = 12;
        long best = -1;
        int bx = 0, by = 0;
        const int mx = R + 2, my = R + 2;
        for (int sy = -R; sy <= R; sy++) {
            for (int sx = -R; sx <= R; sx++) {
                long sad = 0;
                int samples = 0;
                for (int y = my; y < sh_ - my; y += 2) {
                    const uint8_t* a = &refSmall_[(size_t) y * sw_];
                    const uint8_t* b = &small[(size_t) (y + sy) * sw_];
                    for (int x = mx; x < sw_ - mx; x += 2) {
                        sad += std::abs((int) a[x] - (int) b[x + sx]);
                        samples++;
                    }
                }
                if (samples == 0) continue;
                sad = sad * 1000 / samples;
                if (best < 0 || sad < best) { best = sad; bx = sx; by = sy; }
            }
        }
        long best2 = -1;
        int fx = bx * 4, fy = by * 4;
        const int margin = 4 * (R + 2) + 8;
        for (int ry = -4; ry <= 4; ry++) {
            for (int rx = -4; rx <= 4; rx++) {
                int cx = fx + rx, cy = fy + ry;
                long sad = 0;
                int samples = 0;
                for (int y = margin; y < h_ - margin; y += 8) {
                    for (int x = margin; x < w_ - margin; x += 8) {
                        sad += std::abs((int) refY_[(size_t) y * w_ + x] - lumaAt(rgba, stride, x + cx, y + cy));
                        samples++;
                    }
                }
                if (samples == 0) continue;
                sad = sad * 1000 / samples;
                if (best2 < 0 || sad < best2) { best2 = sad; dx = cx; dy = cy; }
            }
        }
        if (best2 < 0) { dx = fx; dy = fy; }

        // local motion: every 64 px tile may move a few pixels away from the global shift
        // (hand shake with rotation, parallax). A penalty keeps tiles on the global shift unless it clearly helps.
        parallelFor(tilesY, [&](int ty0, int ty1) {
            for (int ty = ty0; ty < ty1; ty++) {
                for (int tx = 0; tx < tilesX; tx++) {
                    int xa = tx * tile, xb = std::min(w_, xa + tile), ya = ty * tile, yb = std::min(h_, ya + tile);
                    long bestS = -1;
                    int bxx = dx, byy = dy;
                    for (int ry = -3; ry <= 3; ry++) {
                        for (int rx = -3; rx <= 3; rx++) {
                            int cx = dx + rx, cy = dy + ry;
                            long sad = 0;
                            int samples = 0;
                            for (int y = ya; y < yb; y += 2) {
                                int sy = y + cy;
                                if (sy < 0 || sy >= h_) continue;
                                for (int x = xa; x < xb; x += 2) {
                                    int sx = x + cx;
                                    if (sx < 0 || sx >= w_) continue;
                                    sad += std::abs((int) refY_[(size_t) y * w_ + x] - lumaAt(rgba, stride, sx, sy));
                                    samples++;
                                }
                            }
                            if (samples == 0) continue;
                            sad += (long) ((std::abs(rx) + std::abs(ry)) * samples * 0.7f);
                            if (bestS < 0 || sad < bestS) { bestS = sad; bxx = cx; byy = cy; }
                        }
                    }
                    auto ssd = [&](int cx, int cy) -> double {
                        double sum = 0;
                        int samples = 0;
                        for (int y = ya; y < yb; y += 2) {
                            int sy = y + cy;
                            if (sy < 0 || sy >= h_) continue;
                            for (int x = xa; x < xb; x += 2) {
                                int sx = x + cx;
                                if (sx < 0 || sx >= w_) continue;
                                double d = (double) refY_[(size_t) y * w_ + x] - lumaAt(rgba, stride, sx, sy);
                                sum += d * d;
                                samples++;
                            }
                        }
                        return samples ? sum / samples : -1.0;
                    };
                    float fx = 0.f, fy = 0.f;
                    if (std::abs(bxx - dx) < 3 && std::abs(byy - dy) < 3) {
                        double s0 = ssd(bxx, byy), xm = ssd(bxx - 1, byy), xp = ssd(bxx + 1, byy);
                        double ym = ssd(bxx, byy - 1), yp = ssd(bxx, byy + 1);
                        if (s0 >= 0 && xm >= 0 && xp >= 0 && ym >= 0 && yp >= 0) {
                            double cxq = xm - 2 * s0 + xp, cyq = ym - 2 * s0 + yp;
                            if (cxq > 1.0) fx = clampf((float) ((xm - xp) / (2 * cxq)), -0.5f, 0.5f);
                            if (cyq > 1.0) fy = clampf((float) ((ym - yp) / (2 * cyq)), -0.5f, 0.5f);
                        }
                    }
                    offX[(size_t) ty * tilesX + tx] = (float) bxx + fx;
                    offY[(size_t) ty * tilesX + tx] = (float) byy + fy;
                }
            }
        });
    }
    if (n_ == 0) {
        for (size_t i = 0; i < offX.size(); i++) { offX[i] = 0.f; offY[i] = 0.f; }
    }

    // noise aware motion rejection: a pixel may differ from the running mean by about 3 sigma
    float thrTable[256];
    for (int c = 0; c < 256; c++) thrTable[c] = clampf(3.0f * sigma_ * std::sqrt(1.f + 1.f / std::max(1, c)) + 3.f, 12.f, 70.f);

    const bool warp = n_ > 0;
    parallelFor(h_, [&](int y0, int y1) {
        std::vector<float> rowX((size_t) w_), rowY((size_t) w_);
        for (int y = y0; y < y1; y++) {
            if (warp) {
                float vy = (y + 0.5f) / tile - 0.5f;
                int ty0 = clampi((int) std::floor(vy), 0, tilesY - 1), ty1 = std::min(ty0 + 1, tilesY - 1);
                float wy = clampf(vy - ty0, 0.f, 1.f);
                for (int x = 0; x < w_; x++) {
                    float vx = (x + 0.5f) / tile - 0.5f;
                    int tx0 = clampi((int) std::floor(vx), 0, tilesX - 1), tx1 = std::min(tx0 + 1, tilesX - 1);
                    float wx = clampf(vx - tx0, 0.f, 1.f);
                    size_t i00 = (size_t) ty0 * tilesX + tx0, i01 = (size_t) ty0 * tilesX + tx1;
                    size_t i10 = (size_t) ty1 * tilesX + tx0, i11 = (size_t) ty1 * tilesX + tx1;
                    rowX[x] = (offX[i00] * (1.f - wx) + offX[i01] * wx) * (1.f - wy) + (offX[i10] * (1.f - wx) + offX[i11] * wx) * wy;
                    rowY[x] = (offY[i00] * (1.f - wx) + offY[i01] * wx) * (1.f - wy) + (offY[i10] * (1.f - wx) + offY[i11] * wx) * wy;
                }
            }
            for (int x = 0; x < w_; x++) {
                int pr, pg, pb;
                if (warp) {
                    float fxp = x + rowX[x], fyp = y + rowY[x];
                    if (fxp < 0.f || fyp < 0.f || fxp > w_ - 1.001f || fyp > h_ - 1.001f) continue;
                    int ix = (int) fxp, iy = (int) fyp;
                    float ax = fxp - ix, ay = fyp - iy;
                    float wxs[4], wys[4];
                    cubicWeights(ax, wxs);
                    cubicWeights(ay, wys);
                    float sr = 0.f, sg = 0.f, sb = 0.f;
                    for (int j = 0; j < 4; j++) {
                        const uint8_t* row = rgba + (size_t) clampi(iy - 1 + j, 0, h_ - 1) * stride;
                        float r0 = 0.f, g0 = 0.f, b0 = 0.f;
                        for (int i = 0; i < 4; i++) {
                            const uint8_t* q = row + (size_t) clampi(ix - 1 + i, 0, w_ - 1) * 4;
                            r0 += wxs[i] * q[0];
                            g0 += wxs[i] * q[1];
                            b0 += wxs[i] * q[2];
                        }
                        sr += wys[j] * r0;
                        sg += wys[j] * g0;
                        sb += wys[j] * b0;
                    }
                    pr = clampi((int) (sr + 0.5f), 0, 255);
                    pg = clampi((int) (sg + 0.5f), 0, 255);
                    pb = clampi((int) (sb + 0.5f), 0, 255);
                } else {
                    const uint8_t* p = rgba + (size_t) y * stride + (size_t) x * 4;
                    pr = p[0];
                    pg = p[1];
                    pb = p[2];
                }
                size_t i = (size_t) y * w_ + x;
                uint16_t* a = &acc_[i * 3];
                int c = cnt_[i];
                if (c > 0) {
                    float thr = thrTable[c];
                    float mr = (float) a[0] / c, mg = (float) a[1] / c, mb = (float) a[2] / c;
                    if (std::fabs(pr - mr) > thr || std::fabs(pg - mg) > thr || std::fabs(pb - mb) > thr) continue;
                }
                a[0] += pr;
                a[1] += pg;
                a[2] += pb;
                cnt_[i] = (uint8_t) (c + 1);
            }
        }
    });
    n_++;
    return true;
}

void Merger::finish(uint8_t* rgba, int stride) {
    parallelFor(h_, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++) {
            uint8_t* p = rgba + (size_t) y * stride;
            for (int x = 0; x < w_; x++, p += 4) {
                size_t i = (size_t) y * w_ + x;
                int c = cnt_[i];
                if (c == 0) continue;   // keep the pixel that is already there
                const uint16_t* a = &acc_[i * 3];
                p[0] = (uint8_t) ((a[0] + c / 2) / c);
                p[1] = (uint8_t) ((a[1] + c / 2) / c);
                p[2] = (uint8_t) ((a[2] + c / 2) / c);
                p[3] = 255;
            }
        }
    });
}

// =============================================================== exposure fusion (HDR)

namespace {

inline int pixY(const uint8_t* f, int stride, int x, int y, int w, int h) {
    x = clampi(x, 0, w - 1);
    y = clampi(y, 0, h - 1);
    const uint8_t* p = f + (size_t) y * stride + (size_t) x * 4;
    return (77 * p[0] + 150 * p[1] + 29 * p[2]) >> 8;
}

inline float gradAt(const uint8_t* f, int stride, int x, int y, int w, int h) {
    return (float) (std::abs(pixY(f, stride, x + 1, y, w, h) - pixY(f, stride, x - 1, y, w, h)) +
                    std::abs(pixY(f, stride, x, y + 1, w, h) - pixY(f, stride, x, y - 1, w, h)));
}

// Global shift (dx, dy) of frame b relative to frame a, from the gradient magnitude (it hardly changes with exposure).
void alignGradient(const uint8_t* a, int sa, const uint8_t* b, int sb, int w, int h, int& dx, int& dy) {
    const int S = 4, lw = (w + S - 1) / S, lh = (h + S - 1) / S, R = 12;
    auto lowGrad = [&](const uint8_t* f, int stride, std::vector<float>& g) {
        std::vector<float> L((size_t) lw * lh, 0.f);
        for (int y = 0; y < lh; y++)
            for (int x = 0; x < lw; x++) {
                int sum = 0, c = 0;
                for (int j = 0; j < S; j++)
                    for (int i = 0; i < S; i++) {
                        int xx = x * S + i, yy = y * S + j;
                        if (xx < w && yy < h) { sum += pixY(f, stride, xx, yy, w, h); c++; }
                    }
                L[(size_t) y * lw + x] = c ? (float) sum / c : 0.f;
            }
        g.assign((size_t) lw * lh, 0.f);
        double mean = 0;
        for (int y = 1; y < lh - 1; y++)
            for (int x = 1; x < lw - 1; x++) {
                float v = std::fabs(L[(size_t) y * lw + x + 1] - L[(size_t) y * lw + x - 1]) +
                          std::fabs(L[(size_t) (y + 1) * lw + x] - L[(size_t) (y - 1) * lw + x]);
                g[(size_t) y * lw + x] = v;
                mean += v;
            }
        mean /= std::max(1, (lw - 2) * (lh - 2));
        float inv = mean > 1e-3 ? (float) (1.0 / mean) : 1.f;
        for (auto& v : g) v *= inv;
    };
    std::vector<float> ga, gb;
    lowGrad(a, sa, ga);
    lowGrad(b, sb, gb);
    double best = -1;
    int bx = 0, by = 0;
    const int m = R + 2;
    for (int sy = -R; sy <= R; sy++)
        for (int sx = -R; sx <= R; sx++) {
            double sad = 0;
            int cnt = 0;
            for (int y = m; y < lh - m; y += 2)
                for (int x = m; x < lw - m; x += 2) {
                    sad += std::fabs(ga[(size_t) y * lw + x] - gb[(size_t) (y + sy) * lw + x + sx]);
                    cnt++;
                }
            if (cnt == 0) continue;
            sad /= cnt;
            sad += 0.002 * (std::abs(sx) + std::abs(sy));   // prefer no shift when equally good
            if (best < 0 || sad < best) { best = sad; bx = sx; by = sy; }
        }
    // refine at full resolution (+-3 px) with sparse gradient samples
    const int margin = 4 * (R + 2) + 8;
    double meanA = 0, meanB = 0;
    int cntM = 0;
    for (int y = margin; y < h - margin; y += 8)
        for (int x = margin; x < w - margin; x += 8) {
            meanA += gradAt(a, sa, x, y, w, h);
            meanB += gradAt(b, sb, x + bx * S, y + by * S, w, h);
            cntM++;
        }
    dx = bx * S;
    dy = by * S;
    if (cntM == 0 || meanA < 1e-3 || meanB < 1e-3) return;
    meanA /= cntM;
    meanB /= cntM;
    double best2 = -1;
    for (int ry = -3; ry <= 3; ry++)
        for (int rx = -3; rx <= 3; rx++) {
            double sad = 0;
            for (int y = margin; y < h - margin; y += 8)
                for (int x = margin; x < w - margin; x += 8)
                    sad += std::fabs(gradAt(a, sa, x, y, w, h) / meanA - gradAt(b, sb, x + bx * S + rx, y + by * S + ry, w, h) / meanB);
            if (best2 < 0 || sad < best2) { best2 = sad; dx = bx * S + rx; dy = by * S + ry; }
        }
}

struct Img {
    int w = 0, h = 0, ch = 0;
    std::vector<float> d;
    void init(int W, int H, int C) {
        w = W;
        h = H;
        ch = C;
        d.assign((size_t) W * H * C, 0.f);
    }
    float* plane(int c) { return d.data() + (size_t) c * w * h; }
    const float* plane(int c) const { return d.data() + (size_t) c * w * h; }
};

inline float smooth01(float a, float b, float x) {
    float t = clampf((x - a) / (b - a), 0.f, 1.f);
    return t * t * (3.f - 2.f * t);
}

void downPlane(const float* s, int sw, int sh, float* d, int dw, int dh) {
    static const float k[5] = {1.f / 16.f, 4.f / 16.f, 6.f / 16.f, 4.f / 16.f, 1.f / 16.f};
    std::vector<float> tmp((size_t) dw * sh);
    parallelFor(sh, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++) {
            const float* r = s + (size_t) y * sw;
            float* o = &tmp[(size_t) y * dw];
            for (int x = 0; x < dw; x++) {
                float acc = 0.f;
                for (int i = 0; i < 5; i++) acc += k[i] * r[clampi(2 * x + i - 2, 0, sw - 1)];
                o[x] = acc;
            }
        }
    });
    parallelFor(dh, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++) {
            float* o = d + (size_t) y * dw;
            const float* rows[5];
            for (int j = 0; j < 5; j++) rows[j] = &tmp[(size_t) clampi(2 * y + j - 2, 0, sh - 1) * dw];
            for (int x = 0; x < dw; x++) {
                o[x] = k[0] * rows[0][x] + k[1] * rows[1][x] + k[2] * rows[2][x] + k[3] * rows[3][x] + k[4] * rows[4][x];
            }
        }
    });
}

void downImg(const Img& s, Img& d) {
    d.init((s.w + 1) / 2, (s.h + 1) / 2, s.ch);
    for (int c = 0; c < s.ch; c++) downPlane(s.plane(c), s.w, s.h, d.plane(c), d.w, d.h);
}

inline float upAt(const float* p, int pw, int ph, int x, int y) {
    const int xi = x >> 1, yi = y >> 1;
    const int x0 = std::min(xi, pw - 1), y0 = std::min(yi, ph - 1);
    const int x1 = std::min(x0 + 1, pw - 1), y1 = std::min(y0 + 1, ph - 1);
    const float wx = (x & 1) ? 0.5f : 0.f, wy = (y & 1) ? 0.5f : 0.f;
    const float a = p[(size_t) y0 * pw + x0], b = p[(size_t) y0 * pw + x1];
    const float c = p[(size_t) y1 * pw + x0], e = p[(size_t) y1 * pw + x1];
    const float top = a + (b - a) * wx, bot = c + (e - c) * wx;
    return top + (bot - top) * wy;
}

void downFromFrame(const uint8_t* f, int stride, int w, int h, int dx, int dy, Img& g) {
    static const float k[5] = {1.f / 16.f, 4.f / 16.f, 6.f / 16.f, 4.f / 16.f, 1.f / 16.f};
    g.init((w + 1) / 2, (h + 1) / 2, 3);
    float* pr = g.plane(0);
    float* pg = g.plane(1);
    float* pb = g.plane(2);
    const int gw = g.w;
    parallelFor(g.h, [&](int y0, int y1) {
        std::vector<int> xi(5 * (size_t) gw);
        for (int x = 0; x < gw; x++)
            for (int i = 0; i < 5; i++) xi[(size_t) x * 5 + i] = clampi(2 * x + i - 2 + dx, 0, w - 1) * 4;
        for (int y = y0; y < y1; y++) {
            const uint8_t* rows[5];
            for (int j = 0; j < 5; j++) rows[j] = f + (size_t) clampi(2 * y + j - 2 + dy, 0, h - 1) * stride;
            for (int x = 0; x < gw; x++) {
                const int* ix = &xi[(size_t) x * 5];
                float r = 0.f, gg = 0.f, b = 0.f;
                for (int j = 0; j < 5; j++) {
                    const uint8_t* row = rows[j];
                    float hr = 0.f, hg = 0.f, hb = 0.f;
                    for (int i = 0; i < 5; i++) {
                        const uint8_t* q = row + ix[i];
                        hr += k[i] * q[0];
                        hg += k[i] * q[1];
                        hb += k[i] * q[2];
                    }
                    r += k[j] * hr;
                    gg += k[j] * hg;
                    b += k[j] * hb;
                }
                const size_t o = (size_t) y * gw + x;
                pr[o] = r;
                pg[o] = gg;
                pb[o] = b;
            }
        }
    });
}

struct ExposureMap {
    float f[256];
};

void buildExposureMap(const std::vector<float>& ref, const std::vector<float>& cur, ExposureMap& m) {
    double sum[256] = {0};
    int cnt[256] = {0};
    for (size_t i = 0; i < ref.size(); i++) {
        float a = ref[i], b = cur[i];
        if (a < 0.03f || a > 0.97f || b < 0.03f || b > 0.97f) continue;
        int bin = clampi((int) (a * 255.f + 0.5f), 0, 255);
        sum[bin] += b;
        cnt[bin]++;
    }
    float mean[256];
    for (int i = 0; i < 256; i++) mean[i] = cnt[i] ? (float) (sum[i] / cnt[i]) : -1.f;
    double sum2[256] = {0};
    int cnt2[256] = {0};
    for (size_t i = 0; i < ref.size(); i++) {
        float a = ref[i], b = cur[i];
        if (a < 0.03f || a > 0.97f || b < 0.03f || b > 0.97f) continue;
        int bin = clampi((int) (a * 255.f + 0.5f), 0, 255);
        if (cnt[bin] < 12 || std::fabs(b - mean[bin]) > 0.12f) continue;
        sum2[bin] += b;
        cnt2[bin]++;
    }
    float val[256];
    bool ok[256];
    for (int i = 0; i < 256; i++) {
        ok[i] = cnt2[i] >= 12;
        val[i] = ok[i] ? (float) (sum2[i] / cnt2[i]) : 0.f;
    }
    int first = -1, last = -1;
    for (int i = 0; i < 256; i++)
        if (ok[i]) {
            if (first < 0) first = i;
            last = i;
        }
    if (first < 0) {
        for (int i = 0; i < 256; i++) m.f[i] = i / 255.f;
        return;
    }
    for (int i = 0; i < 256; i++) {
        if (ok[i]) {
            m.f[i] = val[i];
        } else if (i < first) {
            m.f[i] = val[first] * (i / (float) std::max(1, first));
        } else if (i > last) {
            m.f[i] = val[last] + (1.f - val[last]) * ((i - last) / (float) std::max(1, 255 - last));
        } else {
            int lo = i, hi = i;
            while (!ok[lo]) lo--;
            while (!ok[hi]) hi++;
            float t = (i - lo) / (float) (hi - lo);
            m.f[i] = val[lo] + (val[hi] - val[lo]) * t;
        }
    }
    float run = 0.f;
    for (int i = 0; i < 256; i++) {
        run = std::max(run, m.f[i]);
        m.f[i] = run;
    }
}

inline float mapEval(const ExposureMap& m, float a) {
    float f = clampf(a, 0.f, 1.f) * 255.f;
    int i = (int) f;
    if (i >= 255) return m.f[255];
    float t = f - i;
    return m.f[i] + (m.f[i + 1] - m.f[i]) * t;
}

void weightsFromHalf(const Img& g, std::vector<float>& wOut, std::vector<float>& lumaOut) {
    const int w = g.w, h = g.h;
    const size_t n = (size_t) w * h;
    wOut.assign(n, 0.f);
    lumaOut.assign(n, 0.f);
    const float* pr = g.plane(0);
    const float* pg = g.plane(1);
    const float* pb = g.plane(2);
    for (size_t i = 0; i < n; i++) lumaOut[i] = (0.299f * pr[i] + 0.587f * pg[i] + 0.114f * pb[i]) / 255.f;
    parallelFor(h, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++) {
            const float* c = &lumaOut[(size_t) y * w];
            const float* u = &lumaOut[(size_t) clampi(y - 1, 0, h - 1) * w];
            const float* d = &lumaOut[(size_t) clampi(y + 1, 0, h - 1) * w];
            for (int x = 0; x < w; x++) {
                const size_t i = (size_t) y * w + x;
                const float lap = std::fabs(4.f * c[x] - c[clampi(x - 1, 0, w - 1)] - c[clampi(x + 1, 0, w - 1)] - u[x] - d[x]);
                const float r = pr[i] / 255.f, gg = pg[i] / 255.f, b = pb[i] / 255.f;
                const float m = (r + gg + b) * (1.f / 3.f);
                const float sat = std::sqrt(((r - m) * (r - m) + (gg - m) * (gg - m) + (b - m) * (b - m)) * (1.f / 3.f));
                const float er = r - 0.5f, eg = gg - 0.5f, eb = b - 0.5f;
                const float E = std::exp(-(er * er + eg * eg + eb * eb) / 0.08f);
                wOut[i] = (lap + 0.004f) * (sat + 0.03f) * (E + 0.0004f);
            }
        }
    });
}

}  // namespace

bool fuse(uint8_t** frames, const int* strides, int n, int w, int h) {
    if (n < 1 || !frames || w < 8 || h < 8) return false;
    if (n == 1 || w < 64 || h < 64) return true;

    std::vector<int> dx(n, 0), dy(n, 0);
    for (int i = 1; i < n; i++) alignGradient(frames[0], strides[0], frames[i], strides[i], w, h, dx[i], dy[i]);

    const int hw = (w + 1) / 2, hh = (h + 1) / 2;
    const size_t hn = (size_t) hw * hh;
    std::vector<std::vector<float>> W(n), L(n);
    std::vector<std::vector<uint8_t>> Q(n);
    {
        Img g;
        for (int i = 0; i < n; i++) {
            downFromFrame(frames[i], strides[i], w, h, dx[i], dy[i], g);
            weightsFromHalf(g, W[i], L[i]);
            Q[i].resize(hn * 3);
            for (int c = 0; c < 3; c++) {
                const float* pl = g.plane(c);
                uint8_t* q = &Q[i][(size_t) c * hn];
                for (size_t k = 0; k < hn; k++) q[k] = (uint8_t) clampi((int) (pl[k] + 0.5f), 0, 255);
            }
        }
    }

    for (int i = 1; i < n; i++) {
        ExposureMap em[3];
        std::vector<float> ra(hn), rb(hn);
        for (int c = 0; c < 3; c++) {
            const uint8_t* qa = &Q[0][(size_t) c * hn];
            const uint8_t* qb = &Q[i][(size_t) c * hn];
            for (size_t k = 0; k < hn; k++) {
                ra[k] = qa[k] * (1.f / 255.f);
                rb[k] = qb[k] * (1.f / 255.f);
            }
            buildExposureMap(ra, rb, em[c]);
        }
        std::vector<float> M(hn, 0.f), Mb;
        for (size_t k = 0; k < hn; k++) {
            float dmax = 0.f;
            bool any = false;
            for (int c = 0; c < 3; c++) {
                float a = Q[0][(size_t) c * hn + k] * (1.f / 255.f), b = Q[i][(size_t) c * hn + k] * (1.f / 255.f);
                if (a < 0.05f || a > 0.95f || b < 0.04f || b > 0.96f) continue;
                any = true;
                dmax = std::max(dmax, std::fabs(b - mapEval(em[c], a)));
            }
            if (any) M[k] = smooth01(0.11f, 0.24f, dmax);
        }
        boxMean(M, Mb, hw, hh, std::max(2, hw / 120));
        for (size_t k = 0; k < hn; k++) {
            float m = std::min(1.f, 3.f * Mb[k]);
            W[i][k] *= 1.f - 0.97f * m;
        }
    }
    for (size_t k = 0; k < hn; k++) {
        float sum = 0.f;
        for (int i = 0; i < n; i++) sum += W[i][k];
        float inv = sum > 1e-12f ? 1.f / sum : 1.f / n;
        for (int i = 0; i < n; i++) W[i][k] = sum > 1e-12f ? W[i][k] * inv : inv;
    }

    int K = 1;
    {
        int lw = hw, lh = hh;
        while (K < 9 && std::min((lw + 1) / 2, (lh + 1) / 2) >= 6) {
            lw = (lw + 1) / 2;
            lh = (lh + 1) / 2;
            K++;
        }
    }
    std::vector<int> lwv(K + 1), lhv(K + 1);
    lwv[0] = w;
    lhv[0] = h;
    for (int l = 1; l <= K; l++) {
        lwv[l] = (lwv[l - 1] + 1) / 2;
        lhv[l] = (lhv[l - 1] + 1) / 2;
    }

    std::vector<int16_t> R0((size_t) w * h * 3, 0);
    std::vector<Img> R(K + 1);
    for (int l = 1; l <= K; l++) R[l].init(lwv[l], lhv[l], 3);

    for (int i = 0; i < n; i++) {
        Img cur;
        downFromFrame(frames[i], strides[i], w, h, dx[i], dy[i], cur);
        const uint8_t* f = frames[i];
        const int st = strides[i];
        const int ox = dx[i], oy = dy[i];
        const float* w1 = W[i].data();
        const float* c0 = cur.plane(0);
        const float* c1 = cur.plane(1);
        const float* c2 = cur.plane(2);
        parallelFor(h, [&](int y0, int y1) {
            for (int y = y0; y < y1; y++) {
                const uint8_t* row = f + (size_t) clampi(y + oy, 0, h - 1) * st;
                int16_t* o = &R0[(size_t) y * w * 3];
                for (int x = 0; x < w; x++, o += 3) {
                    const uint8_t* q = row + (size_t) clampi(x + ox, 0, w - 1) * 4;
                    const float wt = upAt(w1, hw, hh, x, y);
                    const float l0 = q[0] - upAt(c0, hw, hh, x, y);
                    const float l1 = q[1] - upAt(c1, hw, hh, x, y);
                    const float l2 = q[2] - upAt(c2, hw, hh, x, y);
                    o[0] = (int16_t) clampi((int) o[0] + (int) std::lrintf(8.f * wt * l0), -32000, 32000);
                    o[1] = (int16_t) clampi((int) o[1] + (int) std::lrintf(8.f * wt * l1), -32000, 32000);
                    o[2] = (int16_t) clampi((int) o[2] + (int) std::lrintf(8.f * wt * l2), -32000, 32000);
                }
            }
        });

        std::vector<float> wl(W[i]);
        int cw = hw, ch = hh;
        for (int l = 1; l <= K; l++) {
            Img& acc = R[l];
            if (l < K) {
                Img nxt;
                downImg(cur, nxt);
                std::vector<float> wn((size_t) nxt.w * nxt.h);
                downPlane(wl.data(), cw, ch, wn.data(), nxt.w, nxt.h);
                for (int c = 0; c < 3; c++) {
                    const float* cp = cur.plane(c);
                    const float* np = nxt.plane(c);
                    float* ap = acc.plane(c);
                    const int nw = nxt.w, nh = nxt.h;
                    parallelFor(ch, [&](int y0, int y1) {
                        for (int y = y0; y < y1; y++)
                            for (int x = 0; x < cw; x++) {
                                const size_t idx = (size_t) y * cw + x;
                                ap[idx] += wl[idx] * (cp[idx] - upAt(np, nw, nh, x, y));
                            }
                    });
                }
                cur.d.swap(nxt.d);
                cur.w = nxt.w;
                cur.h = nxt.h;
                wl.swap(wn);
                cw = nxt.w;
                ch = nxt.h;
            } else {
                for (int c = 0; c < 3; c++) {
                    const float* cp = cur.plane(c);
                    float* ap = acc.plane(c);
                    for (size_t k = 0; k < (size_t) cw * ch; k++) ap[k] += wl[k] * cp[k];
                }
            }
        }
    }

    Img F;
    F.w = lwv[K];
    F.h = lhv[K];
    F.ch = 3;
    F.d = R[K].d;
    for (int l = K - 1; l >= 1; l--) {
        Img G;
        G.init(lwv[l], lhv[l], 3);
        for (int c = 0; c < 3; c++) {
            const float* fp = F.plane(c);
            const float* rp = R[l].plane(c);
            float* gp = G.plane(c);
            const int fw = F.w, fh = F.h, gw = G.w;
            parallelFor(G.h, [&](int y0, int y1) {
                for (int y = y0; y < y1; y++)
                    for (int x = 0; x < gw; x++) gp[(size_t) y * gw + x] = rp[(size_t) y * gw + x] + upAt(fp, fw, fh, x, y);
            });
        }
        F = std::move(G);
        R[l].d.clear();
        R[l].d.shrink_to_fit();
    }
    R[K].d.clear();
    R[K].d.shrink_to_fit();

    const float* f0 = F.plane(0);
    const float* f1 = F.plane(1);
    const float* f2 = F.plane(2);
    const int fw = F.w, fh = F.h;
    parallelFor(h, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++) {
            uint8_t* out = frames[0] + (size_t) y * strides[0];
            const int16_t* r = &R0[(size_t) y * w * 3];
            for (int x = 0; x < w; x++, r += 3, out += 4) {
                const float a = upAt(f0, fw, fh, x, y) + r[0] * 0.125f;
                const float b = upAt(f1, fw, fh, x, y) + r[1] * 0.125f;
                const float c = upAt(f2, fw, fh, x, y) + r[2] * 0.125f;
                out[0] = (uint8_t) clampi((int) (a + 0.5f), 0, 255);
                out[1] = (uint8_t) clampi((int) (b + 0.5f), 0, 255);
                out[2] = (uint8_t) clampi((int) (c + 0.5f), 0, 255);
            }
        }
    });
    return true;
}

}  // namespace cam
