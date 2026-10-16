#include "image_processing.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <thread>

namespace cam {

namespace {

inline uint8_t clamp8(int v) { return (uint8_t) (v < 0 ? 0 : (v > 255 ? 255 : v)); }
inline int clampi(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

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

// Box blur of one 8-bit plane, radius r, edge pixels replicated. src and dst may not alias.
void boxBlur(const uint8_t* src, uint8_t* dst, uint8_t* tmp, int w, int h, int r) {
    if (r < 1) {
        std::memcpy(dst, src, (size_t) w * h);
        return;
    }
    const int div = 2 * r + 1;
    // horizontal: src -> tmp
    parallelFor(h, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++) {
            const uint8_t* s = src + (size_t) y * w;
            uint8_t* d = tmp + (size_t) y * w;
            int sum = s[0] * (r + 1);
            for (int i = 1; i <= r; i++) sum += s[std::min(i, w - 1)];
            for (int x = 0; x < w; x++) {
                d[x] = (uint8_t) ((sum + div / 2) / div);
                int add = s[std::min(x + r + 1, w - 1)];
                int sub = s[std::max(x - r, 0)];
                sum += add - sub;
            }
        }
    });
    // vertical: tmp -> dst, processed in column strips
    parallelFor(w, [&](int x0, int x1) {
        std::vector<int> sum(x1 - x0);
        for (int x = x0; x < x1; x++) {
            int s0 = tmp[x] * (r + 1);
            for (int i = 1; i <= r; i++) s0 += tmp[(size_t) std::min(i, h - 1) * w + x];
            sum[x - x0] = s0;
        }
        for (int y = 0; y < h; y++) {
            size_t addRow = (size_t) std::min(y + r + 1, h - 1) * w;
            size_t subRow = (size_t) std::max(y - r, 0) * w;
            uint8_t* d = dst + (size_t) y * w;
            for (int x = x0; x < x1; x++) {
                int& s = sum[x - x0];
                d[x] = (uint8_t) ((s + div / 2) / div);
                s += tmp[addRow + x] - tmp[subRow + x];
            }
        }
    });
}

// ---------------------------------------------------------------- colour planes

void rgbaToYcc(const uint8_t* rgba, int stride, int w, int h, uint8_t* Y, uint8_t* Cb, uint8_t* Cr) {
    parallelFor(h, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++) {
            const uint8_t* p = rgba + (size_t) y * stride;
            size_t o = (size_t) y * w;
            for (int x = 0; x < w; x++, p += 4) {
                int r = p[0], g = p[1], b = p[2];
                Y[o + x] = clamp8((77 * r + 150 * g + 29 * b + 128) >> 8);
                Cb[o + x] = clamp8(((-43 * r - 85 * g + 128 * b + 128) >> 8) + 128);
                Cr[o + x] = clamp8(((128 * r - 107 * g - 21 * b + 128) >> 8) + 128);
            }
        }
    });
}

void yccToRgba(const uint8_t* Y, const uint8_t* Cb, const uint8_t* Cr, uint8_t* rgba, int stride, int w, int h) {
    parallelFor(h, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++) {
            uint8_t* p = rgba + (size_t) y * stride;
            size_t o = (size_t) y * w;
            for (int x = 0; x < w; x++, p += 4) {
                int yy = Y[o + x], cb = Cb[o + x] - 128, cr = Cr[o + x] - 128;
                p[0] = clamp8(yy + ((359 * cr + 128) >> 8));
                p[1] = clamp8(yy - ((88 * cb + 183 * cr + 128) >> 8));
                p[2] = clamp8(yy + ((454 * cb + 128) >> 8));
            }
        }
    });
}

// ---------------------------------------------------------------- white balance + adaptive gamma

void whiteBalanceAndGamma(uint8_t* rgba, int stride, int w, int h, float awb, float gam) {
    if (awb <= 0.f && gam <= 0.f) return;
    // statistics on a sparse grid
    double sr = 0, sg = 0, sb = 0;
    long n = 0;
    for (int y = 0; y < h; y += 4) {
        const uint8_t* p = rgba + (size_t) y * stride;
        for (int x = 0; x < w; x += 4, p += 16) {
            sr += p[0];
            sg += p[1];
            sb += p[2];
            n++;
        }
    }
    if (n == 0) return;
    sr /= n; sg /= n; sb /= n;
    float gr = 1.f, gg = 1.f, gb = 1.f;
    if (awb > 0.f && sr > 1 && sg > 1 && sb > 1) {
        double m = (sr + sg + sb) / 3.0;
        gr = (float) std::min(1.18, std::max(0.85, m / sr));
        gg = (float) std::min(1.18, std::max(0.85, m / sg));
        gb = (float) std::min(1.18, std::max(0.85, m / sb));
        gr = 1.f + (gr - 1.f) * awb;
        gg = 1.f + (gg - 1.f) * awb;
        gb = 1.f + (gb - 1.f) * awb;
    }
    // gamma < 1 brightens. Only dark scenes are lifted, bright ones are left alone.
    float exponent = 1.f;
    if (gam > 0.f) {
        double mean = (0.299 * sr + 0.587 * sg + 0.114 * sb) / 255.0;
        mean = std::min(0.95, std::max(0.02, mean));
        double g = std::log(0.42) / std::log(mean);   // exponent that would bring the mean to 0.42
        g = std::min(1.0, std::max(0.68, g));
        exponent = (float) (1.0 + (g - 1.0) * gam);
    }
    uint8_t lut[256];
    for (int i = 0; i < 256; i++) lut[i] = clamp8((int) std::lround(255.0 * std::pow(i / 255.0, exponent)));
    const int ir = (int) (gr * 256), ig = (int) (gg * 256), ib = (int) (gb * 256);
    parallelFor(h, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++) {
            uint8_t* p = rgba + (size_t) y * stride;
            for (int x = 0; x < w; x++, p += 4) {
                p[0] = lut[clampi((p[0] * ir) >> 8, 0, 255)];
                p[1] = lut[clampi((p[1] * ig) >> 8, 0, 255)];
                p[2] = lut[clampi((p[2] * ib) >> 8, 0, 255)];
            }
        }
    });
}

// ---------------------------------------------------------------- denoise

// Edge preserving 5x5 filter on luma. sigma grows with the strength.
void lumaBilateral(const uint8_t* src, uint8_t* dst, int w, int h, float strength) {
    const float sigma = 4.f + 22.f * strength;
    int rangeW[256];
    for (int d = 0; d < 256; d++) rangeW[d] = (int) (256.f * std::exp(-(d * d) / (2.f * sigma * sigma)));
    int spat[5][5];
    for (int dy = -2; dy <= 2; dy++)
        for (int dx = -2; dx <= 2; dx++) spat[dy + 2][dx + 2] = std::max(1, 4 - (dx * dx + dy * dy) / 2);
    parallelFor(h, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++) {
            for (int x = 0; x < w; x++) {
                int c = src[(size_t) y * w + x];
                int num = 0, den = 0;
                for (int dy = -2; dy <= 2; dy++) {
                    int yy = clampi(y + dy, 0, h - 1);
                    const uint8_t* row = src + (size_t) yy * w;
                    for (int dx = -2; dx <= 2; dx++) {
                        int v = row[clampi(x + dx, 0, w - 1)];
                        int wt = spat[dy + 2][dx + 2] * rangeW[std::abs(v - c)];
                        num += wt * v;
                        den += wt;
                    }
                }
                dst[(size_t) y * w + x] = (uint8_t) (den > 0 ? (num + den / 2) / den : c);
            }
        }
    });
}

// ---------------------------------------------------------------- CLAHE

void clahe(uint8_t* Y, int w, int h, float amount) {
    const int gx = 8, gy = 8;
    const int tw = (w + gx - 1) / gx, th = (h + gy - 1) / gy;
    const float clipFactor = 2.4f;
    std::vector<uint8_t> luts((size_t) gx * gy * 256);
    parallelFor(gx * gy, [&](int t0, int t1) {
        for (int t = t0; t < t1; t++) {
            int tx = t % gx, ty = t / gx;
            int xa = tx * tw, xb = std::min(w, xa + tw), ya = ty * th, yb = std::min(h, ya + th);
            int hist[256] = {0};
            int count = 0;
            for (int y = ya; y < yb; y++)
                for (int x = xa; x < xb; x++) { hist[Y[(size_t) y * w + x]]++; count++; }
            uint8_t* lut = &luts[(size_t) t * 256];
            if (count == 0) { for (int i = 0; i < 256; i++) lut[i] = (uint8_t) i; continue; }
            int clip = std::max(1, (int) (clipFactor * count / 256.f));
            int excess = 0;
            for (int i = 0; i < 256; i++) if (hist[i] > clip) { excess += hist[i] - clip; hist[i] = clip; }
            int add = excess / 256, rem = excess % 256;
            for (int i = 0; i < 256; i++) hist[i] += add + (i < rem ? 1 : 0);
            long cum = 0;
            for (int i = 0; i < 256; i++) {
                cum += hist[i];
                lut[i] = clamp8((int) ((cum * 255 + count / 2) / count));
            }
        }
    });
    parallelFor(h, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++) {
            float fy = (y + 0.5f) / th - 0.5f;
            int ty0 = clampi((int) std::floor(fy), 0, gy - 1), ty1 = std::min(ty0 + 1, gy - 1);
            float wy = std::min(1.f, std::max(0.f, fy - ty0));
            for (int x = 0; x < w; x++) {
                float fx = (x + 0.5f) / tw - 0.5f;
                int tx0 = clampi((int) std::floor(fx), 0, gx - 1), tx1 = std::min(tx0 + 1, gx - 1);
                float wx = std::min(1.f, std::max(0.f, fx - tx0));
                int v = Y[(size_t) y * w + x];
                float a = luts[(size_t) (ty0 * gx + tx0) * 256 + v];
                float b = luts[(size_t) (ty0 * gx + tx1) * 256 + v];
                float c = luts[(size_t) (ty1 * gx + tx0) * 256 + v];
                float d = luts[(size_t) (ty1 * gx + tx1) * 256 + v];
                float top = a + (b - a) * wx, bot = c + (d - c) * wx;
                float eq = top + (bot - top) * wy;
                Y[(size_t) y * w + x] = clamp8((int) std::lround(v + (eq - v) * amount));
            }
        }
    });
}

// ---------------------------------------------------------------- sharpen

void unsharp(uint8_t* Y, uint8_t* blur, uint8_t* tmp, int w, int h, float amount) {
    int r = 1 + std::max(w, h) / 2400;   // about 2 px on a 12 MP photo
    boxBlur(Y, blur, tmp, w, h, r);
    boxBlur(blur, tmp, blur, w, h, r);   // second pass ~ gaussian
    std::memcpy(blur, tmp, (size_t) w * h);
    const float k = 0.4f + 1.2f * amount;
    const int thr = 2;                   // do not amplify the finest noise
    parallelFor(h, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++) {
            for (int x = 0; x < w; x++) {
                size_t i = (size_t) y * w + x;
                int d = (int) Y[i] - (int) blur[i];
                if (std::abs(d) <= thr) continue;
                d += d > 0 ? -thr : thr;
                Y[i] = clamp8((int) std::lround(Y[i] + k * d));
            }
        }
    });
}

}  // namespace

void enhance(uint8_t* rgba, int w, int h, int stride, const EnhanceParams& p) {
    if (!rgba || w < 16 || h < 16) return;
    whiteBalanceAndGamma(rgba, stride, w, h, p.awb, p.gamma);
    if (p.denoise <= 0.f && p.clahe <= 0.f && p.sharpen <= 0.f) return;

    const size_t n = (size_t) w * h;
    std::vector<uint8_t> Y(n), Cb(n), Cr(n), A(n), B(n);
    rgbaToYcc(rgba, stride, w, h, Y.data(), Cb.data(), Cr.data());

    if (p.denoise > 0.f) {
        lumaBilateral(Y.data(), A.data(), w, h, p.denoise);
        Y.swap(A);
        int rc = 2 + (int) std::lround(7.f * p.denoise);
        boxBlur(Cb.data(), A.data(), B.data(), w, h, rc);
        Cb.swap(A);
        boxBlur(Cr.data(), A.data(), B.data(), w, h, rc);
        Cr.swap(A);
    }
    if (p.clahe > 0.f) clahe(Y.data(), w, h, p.clahe);
    if (p.sharpen > 0.f) unsharp(Y.data(), A.data(), B.data(), w, h, p.sharpen);

    yccToRgba(Y.data(), Cb.data(), Cr.data(), rgba, stride, w, h);
}

// =============================================================== multi-frame merge

namespace {

inline int lumaAt(const uint8_t* rgba, int stride, int x, int y) {
    const uint8_t* p = rgba + (size_t) y * stride + (size_t) x * 4;
    return (77 * p[0] + 150 * p[1] + 29 * p[2]) >> 8;
}

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
    acc_.assign((size_t) w * h * 3, 0);
    cnt_.assign((size_t) w * h, 0);
}

bool Merger::add(const uint8_t* rgba, int stride) {
    if (n_ >= 250) return false;
    int dx = 0, dy = 0;
    std::vector<uint8_t> small((size_t) sw_ * sh_);
    smallLuma(rgba, stride, w_, h_, sw_, sh_, small.data());

    if (n_ == 0) {
        refSmall_ = small;
        refY_.resize((size_t) w_ * h_);
        parallelFor(h_, [&](int y0, int y1) {
            for (int y = y0; y < y1; y++)
                for (int x = 0; x < w_; x++) refY_[(size_t) y * w_ + x] = (uint8_t) lumaAt(rgba, stride, x, y);
        });
    } else {
        // coarse search at 1/4 size: +-12 px => +-48 px on the full image
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
        // refine at full resolution around the coarse result
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
    }

    const int rejectThr = 46;   // moving objects: do not blend pixels that differ this much from the running mean
    parallelFor(h_, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++) {
            int sy = y + dy;
            if (sy < 0 || sy >= h_) continue;
            for (int x = 0; x < w_; x++) {
                int sx = x + dx;
                if (sx < 0 || sx >= w_) continue;
                const uint8_t* p = rgba + (size_t) sy * stride + (size_t) sx * 4;
                size_t i = (size_t) y * w_ + x;
                uint16_t* a = &acc_[i * 3];
                int c = cnt_[i];
                if (c > 0) {
                    int mr = a[0] / c, mg = a[1] / c, mb = a[2] / c;
                    if (std::abs(p[0] - mr) > rejectThr || std::abs(p[1] - mg) > rejectThr ||
                        std::abs(p[2] - mb) > rejectThr)
                        continue;
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
