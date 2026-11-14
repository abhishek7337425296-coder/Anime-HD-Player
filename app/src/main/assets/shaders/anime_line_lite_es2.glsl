#version 100
precision mediump float;

uniform sampler2D uTexSampler;
uniform vec2 uTexelSize;
uniform float uStrength;
varying vec2 vTexSamplingCoord;

float luma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

void main() {
    vec2 p = vTexSamplingCoord;
    vec3 center = texture2D(uTexSampler, p).rgb;
    vec3 leftC = texture2D(uTexSampler, p - vec2(uTexelSize.x, 0.0)).rgb;
    vec3 rightC = texture2D(uTexSampler, p + vec2(uTexelSize.x, 0.0)).rgb;
    vec3 upC = texture2D(uTexSampler, p - vec2(0.0, uTexelSize.y)).rgb;
    vec3 downC = texture2D(uTexSampler, p + vec2(0.0, uTexelSize.y)).rgb;

    vec3 localAverage = (leftC + rightC + upC + downC) * 0.25;
    float edge = clamp(abs(luma(center) - luma(localAverage)) * 3.0, 0.0, 1.0);
    vec3 sharpened = center + (center - localAverage) * (uStrength * (0.35 + edge * 0.65));
    gl_FragColor = vec4(clamp(sharpened, 0.0, 1.0), 1.0);
}
