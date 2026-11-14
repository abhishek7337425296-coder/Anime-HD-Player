#version 100
precision highp float;

uniform sampler2D uTexSampler;
uniform vec2 uTexelSize;
uniform float uStrength;
uniform float uContrast;
uniform float uSaturation;
uniform float uSplitX;
uniform float uScanActive;
varying vec2 vTexSamplingCoord;

float luma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

void main() {
    vec2 p = vTexSamplingCoord;
    vec2 dx = vec2(uTexelSize.x, 0.0);
    vec2 dy = vec2(0.0, uTexelSize.y);
    vec3 original = texture2D(uTexSampler, p).rgb;

    // Multi-tap cross, diagonal and wider neighborhood reconstruction.
    vec3 l = texture2D(uTexSampler, p - dx).rgb;
    vec3 r = texture2D(uTexSampler, p + dx).rgb;
    vec3 u = texture2D(uTexSampler, p - dy).rgb;
    vec3 d = texture2D(uTexSampler, p + dy).rgb;
    vec3 ul = texture2D(uTexSampler, p - dx - dy).rgb;
    vec3 ur = texture2D(uTexSampler, p + dx - dy).rgb;
    vec3 dl = texture2D(uTexSampler, p - dx + dy).rgb;
    vec3 dr = texture2D(uTexSampler, p + dx + dy).rgb;
    vec3 l2 = texture2D(uTexSampler, p - 2.0 * dx).rgb;
    vec3 r2 = texture2D(uTexSampler, p + 2.0 * dx).rgb;
    vec3 u2 = texture2D(uTexSampler, p - 2.0 * dy).rgb;
    vec3 d2 = texture2D(uTexSampler, p + 2.0 * dy).rgb;

    vec3 crossBlur = (l + r + u + d) * 0.25;
    vec3 diagonalBlur = (ul + ur + dl + dr) * 0.25;
    vec3 wideBlur = (l2 + r2 + u2 + d2) * 0.25;
    vec3 reconstruction = crossBlur * 0.48 + diagonalBlur * 0.34 + wideBlur * 0.18;
    float edge = clamp(abs(luma(original) - luma(reconstruction)) * 5.0, 0.0, 1.0);
    float lineMask = smoothstep(0.018, 0.20, edge);
    vec3 microDeblur = original + (original - reconstruction) * 0.42;
    vec3 detail = microDeblur - reconstruction;
    // Stronger edge reconstruction and line tightening than Lite.
    vec3 sharpened = microDeblur + detail * uStrength * (0.55 + 1.10 * lineMask);
    float y = luma(sharpened);
    vec3 vivid = mix(vec3(y), sharpened, uSaturation);
    vec3 enhanced = clamp((vivid - vec3(0.16)) * uContrast + vec3(0.16), 0.0, 1.0);

    vec3 result = mix(original, enhanced, step(p.x, uSplitX));
    if (uScanActive > 0.5) {
        float beam = exp(-pow((p.x - uSplitX) / 0.0035, 2.0));
        float halo = exp(-pow((p.x - uSplitX) / 0.015, 2.0));
        result = mix(result, vec3(0.12, 0.90, 1.0), clamp(halo * 0.30, 0.0, 0.30));
        result = mix(result, vec3(0.94, 1.0, 1.0), clamp(beam, 0.0, 1.0));
    }
    gl_FragColor = vec4(result, 1.0);
}
