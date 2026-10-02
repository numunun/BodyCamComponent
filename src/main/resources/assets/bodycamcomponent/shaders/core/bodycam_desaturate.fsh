#version 150

uniform sampler2D Sampler0;
uniform float Saturation;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec3 color = texture(Sampler0, texCoord).rgb;
    float luma = dot(color, vec3(0.299, 0.587, 0.114));

    // 색이 빠질수록 대비도 살짝 낮춰서 흐릿한 느낌
    float fade = 1.0 - Saturation;
    vec3 gray = mix(vec3(luma), vec3(0.45), fade * 0.15);

    fragColor = vec4(mix(gray, color, Saturation), 1.0);
}