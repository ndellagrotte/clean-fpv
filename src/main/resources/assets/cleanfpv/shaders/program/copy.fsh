#version 120

// Clean FPV: opaque copy back into the main target (no blending, alpha forced to 1).

uniform sampler2D DiffuseSampler;

varying vec2 screenUv;

void main() {
    gl_FragColor = vec4(texture2D(DiffuseSampler, screenUv).rgb, 1.0);
}
