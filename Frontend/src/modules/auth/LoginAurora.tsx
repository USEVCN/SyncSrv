// SPDX-License-Identifier: LicenseRef-ReactBits-MIT-Commons-Clause
// Aurora shader adapted from React Bits by David Haz.
// Source: https://github.com/DavidHDev/react-bits/tree/main/src/content/Backgrounds/Aurora
// License: public/licenses/react-bits.txt (MIT + Commons Clause).
import { useEffect, useRef } from 'react'

const VERTEX = `
attribute vec2 position;
void main() { gl_Position = vec4(position, 0.0, 1.0); }
`
const FRAGMENT = `
precision highp float;
uniform float uTime;
uniform vec2 uResolution;
vec3 permute(vec3 x) {
  return mod(((x * 34.0) + 1.0) * x, 289.0);
}

float snoise(vec2 v){
  const vec4 C = vec4(
      0.211324865405187, 0.366025403784439,
      -0.577350269189626, 0.024390243902439
  );
  vec2 i  = floor(v + dot(v, C.yy));
  vec2 x0 = v - i + dot(i, C.xx);
  vec2 i1 = (x0.x > x0.y) ? vec2(1.0, 0.0) : vec2(0.0, 1.0);
  vec4 x12 = x0.xyxy + C.xxzz;
  x12.xy -= i1;
  i = mod(i, 289.0);

  vec3 p = permute(
      permute(i.y + vec3(0.0, i1.y, 1.0))
    + i.x + vec3(0.0, i1.x, 1.0)
  );

  vec3 m = max(
      0.5 - vec3(
          dot(x0, x0),
          dot(x12.xy, x12.xy),
          dot(x12.zw, x12.zw)
      ), 
      0.0
  );
  m = m * m;
  m = m * m;

  vec3 x = 2.0 * fract(p * C.www) - 1.0;
  vec3 h = abs(x) - 0.5;
  vec3 ox = floor(x + 0.5);
  vec3 a0 = x - ox;
  m *= 1.79284291400159 - 0.85373472095314 * (a0*a0 + h*h);

  vec3 g;
  g.x  = a0.x  * x0.x  + h.x  * x0.y;
  g.yz = a0.yz * x12.xz + h.yz * x12.yw;
  return 130.0 * dot(m, g);
}


void main() {
    vec2 uv = gl_FragCoord.xy / uResolution;
    vec3 green = vec3(124.0, 255.0, 103.0) / 255.0;
    vec3 lavender = vec3(180.0, 151.0, 207.0) / 255.0;
    vec3 violet = vec3(82.0, 39.0, 255.0) / 255.0;
    vec3 color = uv.x < 0.5
        ? mix(green, lavender, uv.x * 2.0)
        : mix(lavender, violet, (uv.x - 0.5) * 2.0);
    float height = snoise(vec2(uv.x * 2.0 + uTime * 0.1, uTime * 0.25)) * 0.5;
    float intensity = max(0.0, 0.6 * (uv.y * 2.0 - exp(height) + 0.2));
    float alpha = smoothstep(-0.05, 0.45, intensity);
    gl_FragColor = vec4(intensity * color * alpha, alpha);
}
`

/** Decorative only: a static CSS aurora remains available without WebGL. */
export default function LoginAurora() {
    const hostRef = useRef<HTMLDivElement>(null)

    useEffect(() => {
        const host = hostRef.current
        if (!host || typeof window.WebGLRenderingContext === 'undefined') return
        // A fresh canvas also supports React StrictMode's effect replay.
        const canvas = document.createElement('canvas')
        let context: WebGLRenderingContext | null
        try {
            context = canvas.getContext('webgl', { alpha: true, antialias: false, premultipliedAlpha: true })
        } catch { return }
        if (!context) return
        const gl = context
        const shaders: WebGLShader[] = []
        let program: WebGLProgram | null = null
        let buffer: WebGLBuffer | null = null
        let frame = 0
        let lost = false
        let elapsed = 0
        let lastTime = 0
        const motion = window.matchMedia?.('(prefers-reduced-motion: reduce)')
        const stop = () => {
            cancelAnimationFrame(frame)
            frame = 0
            lastTime = 0
        }
        const dispose = () => {
            stop()
            if (buffer) gl.deleteBuffer(buffer)
            if (program) gl.deleteProgram(program)
            shaders.forEach(shader => gl.deleteShader(shader))
            canvas.remove()
            delete host.dataset.renderer
            gl.getExtension('WEBGL_lose_context')?.loseContext()
        }
        try {
            const compile = (type: number, code: string) => {
                const shader = gl.createShader(type)
                if (!shader) throw new Error('Shader unavailable')
                shaders.push(shader)
                gl.shaderSource(shader, code)
                gl.compileShader(shader)
                if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) throw new Error('Shader unsupported')
                return shader
            }
            program = gl.createProgram()
            if (!program) throw new Error('Program unavailable')
            gl.attachShader(program, compile(gl.VERTEX_SHADER, VERTEX))
            gl.attachShader(program, compile(gl.FRAGMENT_SHADER, FRAGMENT))
            gl.linkProgram(program)
            if (!gl.getProgramParameter(program, gl.LINK_STATUS)) throw new Error('Program unsupported')
            gl.useProgram(program)
            buffer = gl.createBuffer()
            gl.bindBuffer(gl.ARRAY_BUFFER, buffer)
            gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW)
            const position = gl.getAttribLocation(program, 'position')
            gl.enableVertexAttribArray(position)
            gl.vertexAttribPointer(position, 2, gl.FLOAT, false, 0, 0)
        } catch {
            // The login form must work even when graphics are unavailable.
            dispose()
            return
        }
        const timeUniform = gl.getUniformLocation(program, 'uTime')
        const resolutionUniform = gl.getUniformLocation(program, 'uResolution')
        const draw = () => {
            if (lost || document.hidden) return
            gl.uniform1f(timeUniform, 2 + elapsed * 0.65)
            gl.uniform2f(resolutionUniform, canvas.width, canvas.height)
            gl.drawArrays(gl.TRIANGLES, 0, 3)
        }
        const tick = (now: number) => {
            if (lost || motion?.matches || document.hidden) { stop(); return }
            // Cap rendering at 30 fps and 1.4 million pixels on large displays.
            if (!lastTime || now - lastTime >= 1000 / 30) {
                if (lastTime) elapsed += Math.min(now - lastTime, 100) / 1000
                lastTime = now
                draw()
            }
            frame = requestAnimationFrame(tick)
        }
        const resetParallax = () => {
            host.style.setProperty('--aurora-x', '0px')
            host.style.setProperty('--aurora-y', '0px')
        }
        const followPointer = (event: PointerEvent) => {
            if (event.pointerType === 'touch' || motion?.matches || document.hidden) return
            host.style.setProperty('--aurora-x', `${(event.clientX / window.innerWidth - 0.5) * 28}px`)
            host.style.setProperty('--aurora-y', `${(event.clientY / window.innerHeight - 0.5) * 18}px`)
        }
        const syncMotion = () => {
            stop()
            host.dataset.motion = motion?.matches ? 'reduced' : 'full'
            if (motion?.matches || document.hidden) resetParallax()
            draw()
            if (!lost && !motion?.matches && !document.hidden) frame = requestAnimationFrame(tick)
        }
        const resize = () => {
            const { width, height } = host.getBoundingClientRect()
            const ratio = Math.min(window.devicePixelRatio || 1, 1.25, Math.sqrt(1400000 / Math.max(1, width * height)))
            canvas.width = Math.max(1, Math.round(width * ratio))
            canvas.height = Math.max(1, Math.round(height * ratio))
            gl.viewport(0, 0, canvas.width, canvas.height)
            draw()
        }
        const onContextLost = (event: Event) => {
            event.preventDefault()
            lost = true
            stop()
            delete host.dataset.renderer
        }
        canvas.addEventListener('webglcontextlost', onContextLost)
        host.appendChild(canvas)
        host.dataset.renderer = 'webgl'
        const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(resize)
        observer?.observe(host)
        window.addEventListener('resize', resize)
        window.addEventListener('pointermove', followPointer, { passive: true })
        document.documentElement.addEventListener('pointerleave', resetParallax)
        document.addEventListener('visibilitychange', syncMotion)
        motion?.addEventListener('change', syncMotion)
        resize()
        syncMotion()
        return () => {
            observer?.disconnect()
            window.removeEventListener('resize', resize)
            window.removeEventListener('pointermove', followPointer)
            document.documentElement.removeEventListener('pointerleave', resetParallax)
            document.removeEventListener('visibilitychange', syncMotion)
            motion?.removeEventListener('change', syncMotion)
            canvas.removeEventListener('webglcontextlost', onContextLost)
            delete host.dataset.motion
            dispose()
        }
    }, [])

    return <div ref={hostRef} className="auth-login-aurora" aria-hidden="true" />
}
