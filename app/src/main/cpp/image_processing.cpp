#include "image_processing.h"

#include <algorithm>
#include <cmath>
#include <cstring>
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
    g.sigma = noiseFromLuma(rgba, stride, w, h);

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
    float p50 = 0.4f, p98 = 0.95f;
    for (int i = 0; i < 256; i++) {
        cum += hist[i];
        if (cum * 2 >= total && p50 == 0.4f) p50 = i / 255.f;
        if (cum * 100 >= total * 98) { p98 = i / 255.f; break; }
    }
    p50 = clampf(p50, 0.03f, 0.9f);

    const float T = p.clahe, G = p.gamma;
    float e = std::log(0.40f) / std::log(p50);          // exponent that would bring the median to 0.40
    e = clampf(e, 0.62f, 1.0f);
    const float expo = 1.f + (e - 1.f) * G;
    const float ls = 0.50f * T + 0.20f * G;              // shadow lift
    const float hc = 0.55f * T * (p98 > 0.9f ? 1.f : 0.5f);   // highlight recovery
    const float cs = 0.06f + 0.10f * T;                  // gentle S curve
    g.tone = (T > 0.f || G > 0.f);
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
        float s = y * y * (3.f - 2.f * y);
        y = y + cs * (s - y);
        y = clampf(y, 0.f, 1.f);
        run = std::max(run, y);
        g.curve[i] = run;
    }
    g.dg = clampf(1.f + 0.18f * T + 0.10f * p.sharpen, 1.f, 1.35f);
    g.maxGain = 2.2f + 2.2f * G + 1.2f * T;
    g.vib = 0.16f + 0.22f * T + 0.10f * p.awb;

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

// Signal dependent edge preserving 5x5 luma filter. Dark areas are noisier, so they are filtered more.
void lumaDenoise(const float* src, float* dst, int w, int rows, float sigma, float strength) {
    if (strength <= 0.01f) {
        std::memcpy(dst, src, (size_t) w * rows * sizeof(float));
        return;
    }
    const float sr = sigma * (0.8f + 2.4f * strength);
    const float ss = 1.0f + 0.5f * strength;
    float spat[5][5];
    for (int dy = -2; dy <= 2; dy++)
        for (int dx = -2; dx <= 2; dx++) spat[dy + 2][dx + 2] = std::exp(-(dx * dx + dy * dy) / (2.f * ss * ss));
    float lut[200];
    for (int i = 0; i < 200; i++) {
        float t = i / 32.f;
        lut[i] = std::exp(-0.5f * t * t);
    }
    parallelFor(rows, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++) {
            const float* rowsP[5];
            for (int dy = -2; dy <= 2; dy++) rowsP[dy + 2] = src + (size_t) clampi(y + dy, 0, rows - 1) * w;
            float* d = dst + (size_t) y * w;
            for (int x = 0; x < w; x++) {
                float c = rowsP[2][x];
                {
                    // an isolated spike is not part of the picture: compare against the neighbourhood instead
                    float m = 0;
                    for (int dy = 1; dy <= 3; dy++) {
                        const float* rp = rowsP[dy];
                        m += rp[clampi(x - 1, 0, w - 1)] + rp[x] + rp[clampi(x + 1, 0, w - 1)];
                    }
                    m = (m - c) / 8.f;
                    if (std::fabs(c - m) > 2.4f * sr) c = m;
                }
                float inv = 1.f / (sr * (1.f + 0.8f * (1.f - c / 255.f)));
                float num = 0, den = 0;
                for (int dy = 0; dy < 5; dy++) {
                    const float* rp = rowsP[dy];
                    for (int dx = -2; dx <= 2; dx++) {
                        float v = rp[clampi(x + dx, 0, w - 1)];
                        int idx = (int) (std::fabs(v - c) * inv * 32.f);
                        float wt = spat[dy][dx + 2] * (idx < 200 ? lut[idx] : 0.f);
                        num += wt * v;
                        den += wt;
                    }
                }
                d[x] = den > 0 ? num / den : c;
            }
        }
    });
}

// Second luma filter AFTER the tone mapping: the lift multiplies the noise, so the filter strength follows
// the gain of each pixel (dark areas that were brightened a lot get filtered more).
void lumaDenoisePost(const float* src, float* dst, const float* gain, int w, int rows, float noise, float strength) {
    float spat[5][5];
    for (int dy = -2; dy <= 2; dy++)
        for (int dx = -2; dx <= 2; dx++) spat[dy + 2][dx + 2] = std::exp(-(dx * dx + dy * dy) / (2.f * 1.2f * 1.2f));
    float lut[200];
    for (int i = 0; i < 200; i++) {
        float t = i / 32.f;
        lut[i] = std::exp(-0.5f * t * t);
    }
    parallelFor(rows, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++) {
            const float* rowsP[5];
            for (int dy = -2; dy <= 2; dy++) rowsP[dy + 2] = src + (size_t) clampi(y + dy, 0, rows - 1) * w;
            const float* gp = gain + (size_t) y * w;
            float* d = dst + (size_t) y * w;
            for (int x = 0; x < w; x++) {
                float c = rowsP[2][x];
                float sr = noise * std::max(1.f, gp[x]) * (1.1f + 1.4f * strength);
                {
                    float m = 0;
                    for (int dy = 1; dy <= 3; dy++) {
                        const float* rp = rowsP[dy];
                        m += rp[clampi(x - 1, 0, w - 1)] + rp[x] + rp[clampi(x + 1, 0, w - 1)];
                    }
                    m = (m - c) / 8.f;
                    if (std::fabs(c - m) > 2.4f * sr) c = m;
                }
                float inv = 1.f / std::max(0.5f, sr);
                float num = 0, den = 0;
                for (int dy = 0; dy < 5; dy++) {
                    const float* rp = rowsP[dy];
                    for (int dx = -2; dx <= 2; dx++) {
                        float v = rp[clampi(x + dx, 0, w - 1)];
                        int idx = (int) (std::fabs(v - c) * inv * 32.f);
                        float wt = spat[dy][dx + 2] * (idx < 200 ? lut[idx] : 0.f);
                        num += wt * v;
                        den += wt;
                    }
                }
                d[x] = den > 0 ? num / den : c;
            }
        }
    });
}

// Chroma noise removal at half resolution, guided by the luma so colours do not bleed over edges.
void chromaDenoise(float* cb, float* cr, const float* yGuide, int hw, int hr, float sigma, float strength) {
    const float sY = 10.f + 3.f * sigma;
    const float sC = 4.f + 16.f * strength + 1.5f * sigma;
    float lutY[256], lutC[1024];
    for (int i = 0; i < 256; i++) lutY[i] = std::exp(-(float) (i * i) / (2.f * sY * sY));
    for (int i = 0; i < 1024; i++) lutC[i] = std::exp(-(float) (i * 4) / (2.f * sC * sC));
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
                    float nb = 0, nr = 0, den = 0;
                    for (int dy = -2; dy <= 2; dy++) {
                        int yy = clampi(y + dy, 0, hr - 1);
                        for (int dx = -2; dx <= 2; dx++) {
                            size_t j = (size_t) yy * hw + clampi(x + dx, 0, hw - 1);
                            int dY = std::min(255, (int) std::fabs(yGuide[j] - gy));
                            float db = cb[j] - pb, dr = cr[j] - pr;
                            int dC = std::min(1023, (int) ((db * db + dr * dr) * 0.25f));
                            float wt = spat[dy + 2][dx + 2] * lutY[dY] * lutC[dC];
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
    if (p.denoise <= 0.f && p.clahe <= 0.f && p.sharpen <= 0.f && p.awb <= 0.f && p.gamma <= 0.f) return;

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
                        Yt = fb * std::pow(ratio, g.dg);
                        // very bright pixels must not be pushed over the top
                        Yt = std::min(Yt, 1.f);
                    }
                    // keep the colour strength when the luminance is lifted
                    float lift = Yt / Yn;
                    float sat = clampf(std::pow(lift, 0.5f), 0.92f, 1.45f);
                    float mag = std::sqrt(cb * cb + cr * cr) / 90.f;
                    mag = clampf(mag, 0.f, 1.f);
                    float vib = 1.f + g.vib * (1.f - mag) * (1.f - mag);
                    if (cb < -4.f && cb > -52.f && cr > 6.f && cr < 52.f) vib = 1.f + (vib - 1.f) * 0.45f;   // skin
                    float cs = sat * vib;
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

constexpr int kTile = 64;

}  // namespace

Merger::Merger(int w, int h) : w_(w), h_(h), sw_((w + 3) / 4), sh_((h + 3) / 4) {
    acc_.assign((size_t) w * h * 3, 0);
    cnt_.assign((size_t) w * h, 0);
}

bool Merger::add(const uint8_t* rgba, int stride) {
    if (n_ >= 250) return false;
    int dx = 0, dy = 0;
    std::vector<uint8_t> small((size_t) sw_ * sh_);
    smallLuma(rgba, stride, w_, h_, sw_, sh_, small.data());

    const int tilesX = (w_ + kTile - 1) / kTile, tilesY = (h_ + kTile - 1) / kTile;
    std::vector<int16_t> offX((size_t) tilesX * tilesY, 0), offY((size_t) tilesX * tilesY, 0);

    if (n_ == 0) {
        refSmall_ = small;
        refY_.resize((size_t) w_ * h_);
        parallelFor(h_, [&](int y0, int y1) {
            for (int y = y0; y < y1; y++)
                for (int x = 0; x < w_; x++) refY_[(size_t) y * w_ + x] = (uint8_t) lumaAt(rgba, stride, x, y);
        });
        sigma_ = noiseFromLuma(rgba, stride, w_, h_);
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
                    int xa = tx * kTile, xb = std::min(w_, xa + kTile), ya = ty * kTile, yb = std::min(h_, ya + kTile);
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
                    offX[(size_t) ty * tilesX + tx] = (int16_t) bxx;
                    offY[(size_t) ty * tilesX + tx] = (int16_t) byy;
                }
            }
        });
    }
    if (n_ == 0) {
        for (size_t i = 0; i < offX.size(); i++) { offX[i] = 0; offY[i] = 0; }
    }

    // noise aware motion rejection: a pixel may differ from the running mean by about 3 sigma
    float thrTable[256];
    for (int c = 0; c < 256; c++) thrTable[c] = clampf(3.0f * sigma_ * std::sqrt(1.f + 1.f / std::max(1, c)) + 3.f, 12.f, 70.f);

    parallelFor(h_, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++) {
            const int ty = y / kTile;
            for (int x = 0; x < w_; x++) {
                const size_t ti = (size_t) ty * tilesX + x / kTile;
                int sy = y + offY[ti], sx = x + offX[ti];
                if (sy < 0 || sy >= h_ || sx < 0 || sx >= w_) continue;
                const uint8_t* p = rgba + (size_t) sy * stride + (size_t) sx * 4;
                size_t i = (size_t) y * w_ + x;
                uint16_t* a = &acc_[i * 3];
                int c = cnt_[i];
                if (c > 0) {
                    float thr = thrTable[c];
                    float mr = (float) a[0] / c, mg = (float) a[1] / c, mb = (float) a[2] / c;
                    if (std::fabs(p[0] - mr) > thr || std::fabs(p[1] - mg) > thr || std::fabs(p[2] - mb) > thr) continue;
                }
                a[0] += p[0];
                a[1] += p[1];
                a[2] += p[2];
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

}  // namespace cam
