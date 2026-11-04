import type { BrandParticle } from './loginBrandParticles'
// Local, dependency-free WebGL point renderer; not React Bits Pro source.
const VERTEX = `
attribute vec3 aPosition;
attribute vec4 aColor;
uniform vec2 uResolution;
uniform float uDpr;
uniform float uOpacity;
varying vec4 vColor;
void main() {
    float perspective = 420.0 / (420.0 + aPosition.z);
    vec2 position = (aPosition.xy - uResolution * 0.5) * perspective;
    gl_Position = vec4(position / uResolution * vec2(2.0, -2.0), 0.0, 1.0);
    gl_PointSize = uDpr * (1.8 + min(abs(aPosition.z) / 28.0, 1.0) * 0.7) * perspective;
    vColor = vec4(aColor.rgb, aColor.a * uOpacity);
}`
const FRAGMENT = `
precision mediump float;
varying vec4 vColor;
void main() {
    float coverage = 1.0 - smoothstep(0.27, 0.5, length(gl_PointCoord - vec2(0.5)));
    gl_FragColor = vec4(vColor.rgb, vColor.a * coverage);
}`

export function createBrandRenderer(canvas: HTMLCanvasElement) {
    const gl = canvas.getContext('webgl', { alpha: true, antialias: false, premultipliedAlpha: true })
    if (!gl) throw new Error('WebGL unavailable')
    const shaders: WebGLShader[] = [], buffers: WebGLBuffer[] = []
    let program: WebGLProgram | null = null
    const dispose = () => {
        buffers.forEach(buffer => gl.deleteBuffer(buffer))
        if (program) gl.deleteProgram(program)
        shaders.forEach(shader => gl.deleteShader(shader))
        gl.getExtension('WEBGL_lose_context')?.loseContext()
    }
    try {
        const compile = (type: number, source: string) => {
            const shader = gl.createShader(type)
            if (!shader) throw new Error('Shader unavailable')
            shaders.push(shader)
            gl.shaderSource(shader, source); gl.compileShader(shader)
            if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) throw new Error('Shader compile failed')
            return shader
        }
        program = gl.createProgram()
        if (!program) throw new Error('Program unavailable')
        gl.attachShader(program, compile(gl.VERTEX_SHADER, VERTEX))
        gl.attachShader(program, compile(gl.FRAGMENT_SHADER, FRAGMENT))
        gl.linkProgram(program)
        if (!gl.getProgramParameter(program, gl.LINK_STATUS)) throw new Error('Shader link failed')
        gl.useProgram(program)
        const buffer = gl.createBuffer()
        if (!buffer) throw new Error('Buffer unavailable')
        buffers.push(buffer)
        gl.bindBuffer(gl.ARRAY_BUFFER, buffer)
        for (const [name, size, offset] of [['aPosition', 3, 0], ['aColor', 4, 12]] as const) {
            const location = gl.getAttribLocation(program, name)
            gl.enableVertexAttribArray(location)
            gl.vertexAttribPointer(location, size, gl.FLOAT, false, 28, offset)
        }
        const resolution = gl.getUniformLocation(program, 'uResolution')
        const ratio = gl.getUniformLocation(program, 'uDpr')
        const opacity = gl.getUniformLocation(program, 'uOpacity')
        gl.enable(gl.BLEND)
        gl.blendFuncSeparate(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA, gl.ONE, gl.ONE_MINUS_SRC_ALPHA)
        gl.clearColor(0, 0, 0, 0)
        let vertices = new Float32Array(0)
        return {
            resize(width: number, height: number, dpr: number) {
                canvas.width = Math.round(width * dpr); canvas.height = Math.round(height * dpr)
                gl.viewport(0, 0, canvas.width, canvas.height)
                gl.uniform2f(resolution, width, height); gl.uniform1f(ratio, dpr)
            },
            draw(particles: BrandParticle[], mix: number) {
                gl.clear(gl.COLOR_BUFFER_BIT)
                if (!mix) return
                if (vertices.length !== particles.length * 7) vertices = new Float32Array(particles.length * 7)
                particles.forEach((p, index) => {
                    const i = index * 7
                    vertices[i] = p.x; vertices[i + 1] = p.y; vertices[i + 2] = p.z
                    vertices.set(p.color, i + 3)
                })
                gl.uniform1f(opacity, mix)
                gl.bufferData(gl.ARRAY_BUFFER, vertices, gl.DYNAMIC_DRAW)
                gl.drawArrays(gl.POINTS, 0, particles.length)
            },
            dispose,
        }
    } catch (error) { dispose(); throw error }
}
