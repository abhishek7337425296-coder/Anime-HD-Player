#version 100
precision highp float;

uniform sampler2D uTexSampler;
uniform vec2 uTexelSize;
uniform float uStrength;
uniform float uContrast;
uniform float uSaturation;
varying vec2 vTexSamplingCoord;

float luma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

void main() {
    vec2 p = vTexSamplingCoord;
    vec2 dx = vec2(uTexelSize.x, 0.0);
    vec2 dy = vec2(0.0, uTexelSize.y);

    vec3 center = texture2D(uTexSampler, p).rgb;
    vec3 leftC = texture2D(uTexSampler, p - dx).rgb;
    vec3 rightC = texture2D(uTexSampler, p + dx).rgb;
    vec3 upC = texture2D(uTexSampler, p - dy).rgb;
    vec3 downC = texture2D(uTexSampler, p + dy).rgb;
    vec3 upLeft = texture2D(uTexSampler, p - dx - dy).rgb;
    vec3 upRight = texture2D(uTexSampler, p + dx - dy).rgb;
    vec3 downLeft = texture2D(uTexSampler, p - dx + dy).rgb;
    vec3 downRight = texture2D(uTexSampler, p + dx + dy).rgb;

    vec3 crossAverage = (leftC + rightC + upC + downC) * 0.125;
    vec3 diagonalAverage = (upLeft + upRight + downLeft + downRight) * 0.125;
    vec3 localAverage = crossAverage + diagonalAverage;
    float edge = clamp(abs(luma(center) - luma(localAverage)) * 3.8, 0.0, 1.0);
    float lineWeight = smoothstep(0.025, 0.24, edge);
    vec3 detail = center - localAverage;
    vec3 sharpened = center + detail * uStrength * (0.25 + 0.75 * lineWeight);
    float luminance = luma(sharpened);
    vec3 vivid = mix(vec3(luminance), sharpened, uSaturation);
    vec3 contrasted = (vivid - vec3(0.5)) * uContrast + vec3(0.5);
    gl_FragColor = vec4(clamp(contrasted, 0.0, 1.0), 1.0);
}
