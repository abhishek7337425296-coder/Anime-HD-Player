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
    vec3 original = texture2D(uTexSampler, p).rgb;
    vec2 dx = vec2(uTexelSize.x, 0.0);
    vec2 dy = vec2(0.0, uTexelSize.y);
    vec3 leftC = texture2D(uTexSampler, p - dx).rgb;
    vec3 rightC = texture2D(uTexSampler, p + dx).rgb;
    vec3 upC = texture2D(uTexSampler, p - dy).rgb;
    vec3 downC = texture2D(uTexSampler, p + dy).rgb;
    vec3 blur = (leftC + rightC + upC + downC) * 0.25;
    vec3 deblurred = original + (original - blur) * 0.30;
    float edge = clamp(abs(luma(original) - luma(blur)) * 4.0, 0.0, 1.0);
    vec3 sharpened = deblurred + (deblurred - blur) * (uStrength * (0.45 + edge * 0.75));
    float y = luma(sharpened);
    vec3 vivid = mix(vec3(y), sharpened, uSaturation);
    // Contrast pivots around a shadow-safe dark gray to avoid crushing dark scenes.
    vec3 enhanced = clamp((vivid - vec3(0.18)) * uContrast + vec3(0.18), 0.0, 1.0);

    vec3 result = mix(original, enhanced, step(p.x, uSplitX));
    if (uScanActive > 0.5) {
        float beam = exp(-pow((p.x - uSplitX) / 0.0045, 2.0));
        float halo = exp(-pow((p.x - uSplitX) / 0.018, 2.0));
        result = mix(result, vec3(0.20, 0.92, 1.0), clamp(halo * 0.24, 0.0, 0.24));
        result = mix(result, vec3(0.88, 1.0, 1.0), clamp(beam * 0.95, 0.0, 0.95));
    }
    gl_FragColor = vec4(result, 1.0);
}
