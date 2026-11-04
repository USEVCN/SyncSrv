export type BrandParticle = { homeX: number; homeY: number; x: number; y: number; z: number; vx: number; vy: number; vz: number; phase: number; color: number[] };
export type BrandPointer = { active: boolean; x: number; y: number };
// Original particle simulation. Coordinates are CSS pixels; colors come from
// the rendered brand, so the SVG and lettering are not approximated shapes.
export function sampleBrandPixels({ data, width, height }: Pick<ImageData, "data" | "width" | "height">, gap = 1.4, limit = 6000) {
    const particles: BrandParticle[] = []
    const step = Math.max(1, gap, Math.sqrt(width * height / (limit * 4)))
    for (let y = 0; y < height; y += step) {
        for (let x = 0; x < width; x += step) {
            const index = (Math.floor(y) * width + Math.floor(x)) * 4
            if (data[index + 3] < 100) continue
            const phase = ((Math.floor(x) * 127 + Math.floor(y) * 269) % 997) / 997 * Math.PI * 2
            particles.push({
                homeX: x, homeY: y, x, y, z: 0, vx: 0, vy: 0, vz: 0, phase,
                color: [data[index] / 255, data[index + 1] / 255, data[index + 2] / 255, data[index + 3] / 255],
            })
        }
    }
    if (particles.length <= limit) return particles
    // Distribute the budget over the whole silhouette, not just its first rows.
    return Array.from({ length: limit }, (_, i) => particles[Math.floor(i * particles.length / limit)])
}

export function advanceBrandParticles(particles: BrandParticle[], pointer: BrandPointer | null, deltaMs: number) {
    const dt = Math.min(2, Math.max(0, deltaMs / (1000 / 60)))
    const damping = Math.pow(0.8, dt)
    let energy = 0
    for (const p of particles) {
        let forceX = 0, forceY = 0, forceZ = 0
        if (pointer?.active) {
            const dx = p.homeX - pointer.x, dy = p.homeY - pointer.y
            const distance = Math.hypot(dx, dy)
            if (distance < 42) {
                const force = Math.pow(1 - distance / 42, 2) * 3.8
                const nx = distance > 0.1 ? dx / distance : Math.cos(p.phase)
                const ny = distance > 0.1 ? dy / distance : Math.sin(p.phase)
                forceX = (nx + Math.sin(p.phase) * 0.28) * force
                forceY = (ny + Math.cos(p.phase) * 0.28) * force
                forceZ = Math.sin(p.phase) * force * 0.85
            }
        }
        p.vx = (p.vx + ((p.homeX - p.x) * 0.065 + forceX) * dt) * damping
        p.vy = (p.vy + ((p.homeY - p.y) * 0.065 + forceY) * dt) * damping
        p.vz = (p.vz + (-p.z * 0.065 + forceZ) * dt) * damping
        p.x += p.vx * dt; p.y += p.vy * dt; p.z += p.vz * dt
        energy = Math.max(energy, Math.abs(p.x - p.homeX), Math.abs(p.y - p.homeY), Math.abs(p.z), Math.abs(p.vx), Math.abs(p.vy), Math.abs(p.vz))
    }
    return energy
}
