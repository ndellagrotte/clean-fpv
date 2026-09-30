#version 120

// Clean FPV: full-screen pass vertex stage. The post chain draws one quad in output pixel
// coordinates (origin top-left, y down through ProjMat); derive a texture coordinate with the
// origin at the bottom-left, as framebuffer textures are stored.

attribute vec4 Position;

uniform mat4 ProjMat;
uniform vec2 OutSize;

varying vec2 screenUv;

void main() {
    vec4 clip = ProjMat * vec4(Position.xy, 0.0, 1.0);
    gl_Position = vec4(clip.xy, 0.2, 1.0);
    screenUv = vec2(Position.x / OutSize.x, 1.0 - Position.y / OutSize.y);
}
