#pragma once
#include <cstdint>
#include <vector>

namespace cam {

// All buffers are RGBA, 8 bits per channel (the memory layout of an ARGB_8888 Android Bitmap).

struct EnhanceParams {
    float denoise = 0.f;   // 0..1  noise reduction (signal dependent luma filter + guided chroma filter)
    float clahe = 0.f;     // 0..1  local tone mapping (shadow lift, highlight recovery, local contrast)
    float sharpen = 0.f;   // 0..1  two scale sharpening with noise coring and halo limit
    float awb = 0.f;       // 0..1  white balance correction
    float gamma = 0.f;     // 0..1  adaptive exposure lift (dark scenes only)
};

// In place, tile (band) based: memory use does not grow with the picture size, so it also works for 50 MP.
void enhance(uint8_t* rgba, int w, int h, int strideBytes, const EnhanceParams& p);

// Noise level (standard deviation in 0..255 luma units) estimated from the picture itself.
float estimateNoise(const uint8_t* rgba, int w, int h, int strideBytes);

// Multi-frame merge: the first frame is the reference, every next frame is aligned to it with a
// per-tile motion search and averaged with noise-aware motion rejection.
class Merger {
public:
    Merger(int w, int h);
    bool add(const uint8_t* rgba, int strideBytes);
    void finish(uint8_t* rgba, int strideBytes);
    int frames() const { return n_; }
    int width() const { return w_; }
    int height() const { return h_; }

private:
    int w_, h_, n_ = 0;
    std::vector<uint16_t> acc_;     // w*h*3 running sums
    std::vector<uint8_t> cnt_;      // w*h number of frames that contributed
    std::vector<uint8_t> refY_;     // full-resolution luma of the reference
    std::vector<uint8_t> refSmall_; // luma of the reference at 1/4 size
    int sw_, sh_;
    float sigma_ = 4.f;             // noise level of the reference
};

}  // namespace cam
