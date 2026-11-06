// Flou gaussien séparable à 9 échantillons (poids linéarisés).
#ifdef GL_ES
precision mediump float;
#endif
varying vec4 v_color;
varying vec2 v_texCoords;
uniform sampler2D u_texture;
uniform vec2 u_dir;

void main() {
    vec4 sum = texture2D(u_texture, v_texCoords) * 0.2270270270;
    vec2 o1 = u_dir * 1.3846153846;
    vec2 o2 = u_dir * 3.2307692308;
    sum += texture2D(u_texture, v_texCoords + o1) * 0.3162162162;
    sum += texture2D(u_texture, v_texCoords - o1) * 0.3162162162;
    sum += texture2D(u_texture, v_texCoords + o2) * 0.0702702703;
    sum += texture2D(u_texture, v_texCoords - o2) * 0.0702702703;
    gl_FragColor = sum * v_color;
}
