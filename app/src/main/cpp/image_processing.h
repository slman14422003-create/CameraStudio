#pragma once
#include <cstdint>
#include <vector>

namespace cam {

// All buffers are RGBA, 8 bits per channel (the memory layout of an ARGB_8888 Android Bitmap).

struct EnhanceParams {
    float denoise = 0.f;   // 0..1  noise reduction (luma bilateral + chroma blur)
    float clahe = 0.f;     // 0..1  local contrast (CLAHE on luma)
    float sharpen = 0.f;   // 0..1  unsharp mask on luma
    float awb = 0.f;       // 0..1  gray-world white balance
    float gamma = 0.f;     // 0..1  adaptive gamma (lifts dark scenes only)
};

void enhance(uint8_t* rgba, int w, int h, int strideBytes, const EnhanceParams& p);

// Multi-frame merge (night mode): the first frame is the reference, every next frame is aligned to it
// (translation only) and averaged with motion rejection.
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
};

}  // namespace cam
