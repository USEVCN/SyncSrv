import { useEffect, useRef } from 'react'
import { Typography } from 'antd'

import { sampleBrandPixels, advanceBrandParticles, type BrandParticle } from './loginBrandParticles'
import { createBrandRenderer } from './loginBrandRenderer'
import './LoginParticleBrand.css'

const PADDING = 40

function sampleBrand(host: HTMLDivElement) {
    const rect = host.getBoundingClientRect()
    const image = host.querySelector('img')
    const title = host.querySelector('h2')
    if (!image || !title) throw new Error('Brand unavailable')
    const canvas = document.createElement('canvas')
    canvas.width = Math.ceil(rect.width + PADDING * 2)
    canvas.height = Math.ceil(rect.height + PADDING * 2)
    const context = canvas.getContext('2d', { willReadFrequently: true })
    if (!context) throw new Error('Sampling unavailable')
    const logoRect = image.getBoundingClientRect()
    context.drawImage(image, logoRect.left - rect.left + PADDING, logoRect.top - rect.top + PADDING, logoRect.width, logoRect.height)
    const titleRect = title.getBoundingClientRect()
    const style = getComputedStyle(title)
    context.font = `${style.fontWeight} ${style.fontSize} ${style.fontFamily}`
    context.fillStyle = style.color
    context.textAlign = 'center'
    context.textBaseline = 'alphabetic'
    if ('letterSpacing' in context) context.letterSpacing = style.letterSpacing
    const text = title.textContent || ""
    const metrics = context.measureText(text)
    const ascent = metrics.fontBoundingBoxAscent ?? metrics.actualBoundingBoxAscent
    const descent = metrics.fontBoundingBoxDescent ?? metrics.actualBoundingBoxDescent
    const baseline = titleRect.top - rect.top + PADDING + (titleRect.height - ascent - descent) / 2 + ascent
    context.fillText(text, titleRect.left - rect.left + PADDING + titleRect.width / 2, baseline)
    return { width: canvas.width, height: canvas.height, particles: sampleBrandPixels(context.getImageData(0, 0, canvas.width, canvas.height)) }
}

// Keep real SVG/text in the DOM for a crisp idle state, accessibility and fallback.
// Only the brand area is interactive. The canvas never receives form events.
export default function LoginParticleBrand() {
    const hostRef = useRef<HTMLDivElement>(null)
    useEffect(() => {
        const host = hostRef.current
        if (!host) return
        const motion = window.matchMedia?.('(prefers-reduced-motion: reduce)')
        const finePointer = window.matchMedia?.('(hover: hover) and (pointer: fine)')
        let alive = true, disposeSession = () => {}, generation = 0
        const reset = () => {
            host.style.setProperty('--brand-particle-mix', '0')
            delete host.dataset.particleCount
        }
        const sync = async () => {
            const current = ++generation
            disposeSession(); disposeSession = () => {}; reset()
            host.dataset.particleState = 'static'
            if (motion?.matches || !finePointer?.matches || typeof window.WebGLRenderingContext === 'undefined') return
            try {
                await Promise.all([host.querySelector('img')?.decode(), document.fonts?.ready])
            } catch { return }
            if (!alive || current !== generation) return
            const canvas = document.createElement('canvas')
            canvas.className = 'auth-login-brand-particles'
            canvas.setAttribute('aria-hidden', 'true')
            let renderer: ReturnType<typeof createBrandRenderer>
            try { renderer = createBrandRenderer(canvas) } catch { return }
            host.append(canvas)
            let frame = 0, lastTime = 0, mix = 0, disposed = false
            let particles: BrandParticle[] = []
            const pointer = { active: false, x: 0, y: 0 }
            const stop = () => { cancelAnimationFrame(frame); frame = 0; lastTime = 0 }
            const rest = () => {
                stop(); pointer.active = false; mix = 0
                for (const particle of particles) {
                    particle.x = particle.homeX; particle.y = particle.homeY
                    particle.z = particle.vx = particle.vy = particle.vz = 0
                }
                host.style.setProperty('--brand-particle-mix', '0')
                host.dataset.particleState = 'ready'
                renderer.draw(particles, 0)
            }
            const tick = (now: number) => {
                frame = 0
                if (disposed) return
                if (document.hidden) { rest(); return }
                const delta = lastTime ? Math.min(32, now - lastTime) : 16.67
                lastTime = now
                const energy = advanceBrandParticles(particles, pointer, delta)
                const target = pointer.active || energy > 0.3 ? 1 : 0
                mix += (target - mix) * (1 - Math.exp(-delta / 75))
                host.style.setProperty('--brand-particle-mix', String(mix))
                renderer.draw(particles, mix)
                if (!pointer.active && energy < 0.08 && mix < 0.01) { rest(); return }
                frame = requestAnimationFrame(tick)
            }
            const wake = () => { if (!frame && !document.hidden && !disposed) frame = requestAnimationFrame(tick) }
            const sample = () => {
                if (disposed) return
                rest()
                try {
                    const result = sampleBrand(host)
                    particles = result.particles
                    renderer.resize(result.width, result.height, Math.min(window.devicePixelRatio || 1, 2))
                    host.dataset.particleCount = String(particles.length)
                } catch { disposeSession(); host.dataset.particleState = 'static' }
            }
            const move = (event: PointerEvent) => {
                if (event.pointerType === 'touch' || !particles.length) return
                const rect = host.getBoundingClientRect()
                pointer.x = event.clientX - rect.left + PADDING
                pointer.y = event.clientY - rect.top + PADDING
                pointer.active = true
                host.dataset.particleState = 'active'; wake()
            }
            const leave = () => { pointer.active = false; host.dataset.particleState = 'settling'; wake() }
            const visibility = () => { if (document.hidden) rest() }
            const lost = (event: Event) => { event.preventDefault(); disposeSession(); host.dataset.particleState = 'static' }
            const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(sample)
            disposeSession = () => {
                if (disposed) return
                disposed = true; stop(); reset(); observer?.disconnect()
                host.removeEventListener('pointermove', move); host.removeEventListener('pointerleave', leave)
                host.removeEventListener('pointercancel', leave)
                window.removeEventListener('blur', leave); window.removeEventListener('resize', sample)
                document.removeEventListener('visibilitychange', visibility)
                canvas.removeEventListener('webglcontextlost', lost)
                renderer.dispose(); canvas.remove()
            }
            canvas.addEventListener('webglcontextlost', lost)
            host.addEventListener('pointermove', move); host.addEventListener('pointerleave', leave)
            host.addEventListener('pointercancel', leave)
            window.addEventListener('blur', leave); window.addEventListener('resize', sample)
            document.addEventListener('visibilitychange', visibility)
            observer?.observe(host); sample()
        }
        sync()
        motion?.addEventListener('change', sync); finePointer?.addEventListener('change', sync)
        return () => {
            alive = false; generation += 1; disposeSession(); reset()
            motion?.removeEventListener('change', sync); finePointer?.removeEventListener('change', sync)
        }
    }, [])

    return <div ref={hostRef} className="auth-login-brand auth-login-particle-brand">
        <img src="/system-logo.svg" alt="SyncSrv" className="auth-login-brand-source" width={72} height={72}
            style={{ display: 'block', maxWidth: '100%', flexShrink: 0, objectFit: 'contain', width: 'var(--login-logo-size)', height: 'var(--login-logo-size)' }} />
        <Typography.Title level={2} className="auth-login-title auth-login-brand-source">SyncSrv</Typography.Title>
    </div>
}
