// Le plasma. La texture est une carte d'écoulement :
//   r = position le long du conduit (0 au port a, 1 au port b)
//   g = masque de la fenêtre, b = profil transversal (relief).
// La couleur du sprite transporte les paramètres, sans casser le lot :
//   r = remplissage 0..1, g = sens (0 : depuis a, 1 : depuis b),
//   b = intensité (0,5 = normale), a = opacité.
#ifdef GL_ES
precision mediump float;
#endif
varying vec4 v_color;
varying vec2 v_texCoords;
uniform sampler2D u_texture;
uniform float u_time;
uniform vec3 u_deep;
uniform vec3 u_bright;
uniform float u_glowOnly;

void main() {
    vec4 f = texture2D(u_texture, v_texCoords);
    float t = mix(f.r, 1.0 - f.r, step(0.5, v_color.g));
    float fill = v_color.r;
    float filled = smoothstep(fill + 0.012, fill - 0.012, t);
    float mask = f.g * filled;
    float prof = clamp(f.b, 0.0, 1.0);
    vec3 col = mix(u_deep, u_bright, pow(prof, 0.6));
    float wave = 0.5 + 0.5 * sin((t * 5.0 - u_time * 1.5) * 6.2831);
    float wave2 = 0.5 + 0.5 * sin((t * 11.0 - u_time * 2.7) * 6.2831 + prof * 2.5);
    col *= 0.84 + 0.16 * wave + 0.10 * wave2;
    float front = exp(-pow((t - fill) / 0.06, 2.0)) * step(fill, 0.995);
    col += vec3(0.55, 0.85, 1.0) * front * 0.8;
    float intensity = 0.4 + 1.2 * v_color.b;
    col *= intensity;
    // Passe « lueur » : seulement le cœur brillant, pour le bloom.
    col = mix(col, col * prof * prof * 1.4, u_glowOnly);
    gl_FragColor = vec4(col, mask * v_color.a);
}
