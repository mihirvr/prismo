import { useEffect, useRef } from 'react'

// The moving background: every colour of the logo, flowing slowly like the heat in a lava lamp (the randomness,
// not the blobs). A small WebGL shader: noise that warps itself, mapped onto the logo's colours in the order they
// sit on the colour wheel, so neighbouring colours blend (cyan into blue, pink into orange) and never clash.
// The page blurs it and lays a veil over it (styles.css: .bg .gl, .veil). Phones, reduced motion and data saver get
// the still picture instead (App.jsx).

// Sampled from public/brand/prismo-mark.png (average colour per hue range): cyan, blue, indigo, purple, pink, rose,
// orange, yellow.
const LOGO = ['#16affb', '#1a59ee', '#5a3df0', '#b72ae8', '#e43bba', '#f64b77', '#fc7842', '#fbbe43']
// Yellow is listed twice so it gets a band of its own (a pure yellow stretch where the walk turns back): with one
// share in eight, orange and red outshone it under the page's dark veil.
const LOOP = [...LOGO, '#ffd23f']
const N = LOOP.length

// Light pages lift the colours a little towards white, dark pages deepen them a little.
const MOODS = { light: { lift: 0.12, gain: 1.0 }, dark: { lift: 0.0, gain: 0.92 } }

// Same pace as before (shadergradient's uSpeed 0.035): the movement should only be noticed when you look for it.
const SPEED = 0.035

const VERTEX = `
attribute vec2 p;
void main() { gl_Position = vec4(p, 0.0, 1.0); }`

const FRAGMENT = `
precision mediump float;
uniform vec2 size;
uniform float time;
uniform vec3 colours[${N}];
uniform float lift;
uniform float gain;

float hash(vec2 p) {
  return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

// Smooth value noise, 0..1.
float noise(vec2 p) {
  vec2 i = floor(p), f = fract(p);
  vec2 u = f * f * (3.0 - 2.0 * f);
  float a = hash(i), b = hash(i + vec2(1.0, 0.0)), c = hash(i + vec2(0.0, 1.0)), d = hash(i + vec2(1.0, 1.0));
  return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(vec2 p) {
  float v = 0.0, amp = 0.5;
  for (int k = 0; k < 4; k++) { v += amp * noise(p); p = p * 2.03 + vec2(17.0, 9.0); amp *= 0.5; }
  return v;
}

// The palette walked there and back: x in 0..1 goes cyan > blue > ... > yellow, then back down to cyan. Never
// straight from yellow to cyan: mixing those made an olive green that isn't in the logo (seen in a screenshot).
vec3 palette(float x) {
  float y = fract(x * 0.5) * 2.0;
  y = y < 1.0 ? y : 2.0 - y;
  float s = y * ${N - 1}.0;
  float i = min(floor(s), ${N - 2}.0);
  float f = smoothstep(0.0, 1.0, s - i);
  vec3 a = colours[0], b = colours[1];
  for (int k = 0; k < ${N - 1}; k++) {
    if (float(k) == i) { a = colours[k]; b = colours[k + 1]; }
  }
  return mix(a, b, f);
}

void main() {
  vec2 uv = gl_FragCoord.xy / size;
  // Scaled by the shorter side, so a tall phone screen shows as many colour bands top to bottom as a wide screen
  // does across (scaled by the height, a phone only showed a narrow slice: two or three colours).
  vec2 p = (uv - 0.5) * size / min(size.x, size.y) * 1.6;
  float t = time;
  // Noise warping noise (domain warping): slow, wandering shapes with no fixed rhythm.
  vec2 q = vec2(fbm(p + vec2(0.0, t)), fbm(p + vec2(5.2, 1.3) - t * 0.7));
  vec2 r = vec2(fbm(p + 2.2 * q + vec2(1.7, 9.2) + t * 0.6), fbm(p + 2.2 * q + vec2(8.3, 2.8) - t * 0.5));
  float v = fbm(p + 2.4 * r);
  // The noise sits mostly around 0.3 to 0.7, so it is stretched to walk most of the colours across the screen:
  // with only a little stretch, the warm half of the logo (orange, yellow) hardly showed (checked in a screenshot).
  vec3 c = palette(v * 3.0 + r.x * 1.1 + q.y * 0.5 + t * 0.15);
  c = mix(c, vec3(1.0), lift) * gain;
  gl_FragColor = vec4(c, 1.0);
}`

function rgb(hex) {
  const n = parseInt(hex.slice(1), 16)
  return [(n >> 16) / 255, ((n >> 8) & 255) / 255, (n & 255) / 255]
}

export default function Gradient({ theme, onReady }) {
  const canvas = useRef(null)
  const mood = useRef(MOODS[theme])
  mood.current = MOODS[theme] ?? MOODS.dark

  useEffect(() => {
    const el = canvas.current
    const gl = el.getContext('webgl', { antialias: false, alpha: false, powerPreference: 'low-power' })
    if (!gl) { onReady(); return undefined }
    const shader = (type, src) => { const s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s); return s }
    const prog = gl.createProgram()
    gl.attachShader(prog, shader(gl.VERTEX_SHADER, VERTEX))
    gl.attachShader(prog, shader(gl.FRAGMENT_SHADER, FRAGMENT))
    gl.linkProgram(prog)
    if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) { onReady(); return undefined }
    gl.useProgram(prog)
    gl.bindBuffer(gl.ARRAY_BUFFER, gl.createBuffer())
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW)
    const at = gl.getAttribLocation(prog, 'p')
    gl.enableVertexAttribArray(at)
    gl.vertexAttribPointer(at, 2, gl.FLOAT, false, 0, 0)
    const u = (n) => gl.getUniformLocation(prog, n)
    gl.uniform3fv(u('colours'), new Float32Array(LOOP.flatMap(rgb)))

    // Drawn small (the page blurs it anyway): cheap on the GPU, like shadergradient's pixelDensity 0.6.
    const scale = 0.35
    const resize = () => {
      el.width = Math.max(2, Math.round(el.clientWidth * scale))
      el.height = Math.max(2, Math.round(el.clientHeight * scale))
      gl.viewport(0, 0, el.width, el.height)
    }
    resize()
    addEventListener('resize', resize)

    // A random start, so each visit begins somewhere else in the flow.
    const offset = Math.random() * 1000
    const start = performance.now()
    let raf = 0, first = true, last = 0
    // Phones and small windows: 30 frames a second is plenty for this slow movement, and saves battery.
    const small = matchMedia('(max-width: 860px)')
    const frame = (now) => {
      if (!first && small.matches && now - last < 30) { raf = requestAnimationFrame(frame); return }
      last = now
      gl.uniform2f(u('size'), el.width, el.height)
      gl.uniform1f(u('time'), offset + ((now - start) / 1000) * SPEED)
      gl.uniform1f(u('lift'), mood.current.lift)
      gl.uniform1f(u('gain'), mood.current.gain)
      gl.drawArrays(gl.TRIANGLES, 0, 3)
      if (first) { first = false; onReady() }
      raf = requestAnimationFrame(frame)
    }
    raf = requestAnimationFrame(frame)
    return () => { cancelAnimationFrame(raf); removeEventListener('resize', resize) }
  }, [onReady])

  return <canvas ref={canvas} style={{ position: 'absolute', inset: 0, width: '100%', height: '100%' }} aria-hidden="true" />
}
