// Fond animé : dégradé bleu nuit, grille d'atelier en perspective douce,
// lueurs qui dérivent lentement, grain léger. Calculé en coordonnées écran.
#ifdef GL_ES
precision mediump float;
#endif
varying vec4 v_color;
varying vec2 v_texCoords;
uniform vec2 u_resolution;
uniform float u_time;
uniform float u_scale;
uniform vec3 u_accent;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
    vec2 uv = gl_FragCoord.xy / u_resolution;
    vec3 top = vec3(0.035, 0.055, 0.115);
    vec3 bottom = vec3(0.012, 0.020, 0.050);
    vec3 col = mix(bottom, top, uv.y);
    // Grille fine, plus marquée vers le bas.
    vec2 g = gl_FragCoord.xy / (48.0 * u_scale);
    g.y += u_time * 0.05;
    vec2 gf = abs(fract(g) - 0.5);
    float line = smoothstep(0.48, 0.5, max(gf.x, gf.y));
    col += vec3(0.10, 0.22, 0.35) * line * (0.10 + 0.12 * (1.0 - uv.y));
    // Deux lueurs qui dérivent.
    vec2 c1 = vec2(0.25 + 0.10 * sin(u_time * 0.11), 0.78 + 0.05 * cos(u_time * 0.13));
    vec2 c2 = vec2(0.80 + 0.08 * cos(u_time * 0.09), 0.22 + 0.06 * sin(u_time * 0.07));
    vec2 aspect = vec2(u_resolution.x / u_resolution.y, 1.0);
    float d1 = length((uv - c1) * aspect);
    float d2 = length((uv - c2) * aspect);
    col += u_accent * 0.10 * exp(-d1 * d1 * 6.0);
    col += vec3(0.55, 0.20, 0.65) * 0.06 * exp(-d2 * d2 * 7.0);
    // Grain pour éviter les bandes de dégradé.
    col += (hash(gl_FragCoord.xy + fract(u_time)) - 0.5) * 0.012;
    gl_FragColor = vec4(col, 1.0) * v_color;
}
