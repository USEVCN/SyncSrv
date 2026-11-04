// SPDX-License-Identifier: LicenseRef-ReactBits-MIT-Commons-Clause
// Adapted from React Bits Border Glow by David Haz; see public/licenses/react-bits.txt.
import type { ReactNode, PointerEvent } from 'react'
import './LoginBorderGlow.css'

export default function LoginBorderGlow({ children }: { children: ReactNode }) {
    const followPointer = (event: PointerEvent<HTMLDivElement>) => {
        if (event.pointerType === 'touch' || window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) return
        const element = event.currentTarget
        const rect = element.getBoundingClientRect()
        const x = event.clientX - rect.left - rect.width / 2
        const y = event.clientY - rect.top - rect.height / 2
        const proximity = Math.min(1, Math.max(Math.abs(x) / (rect.width / 2), Math.abs(y) / (rect.height / 2)))
        element.style.setProperty('--edge-proximity', String(proximity * 100))
        element.style.setProperty('--cursor-angle', `${Math.atan2(y, x) * 180 / Math.PI + 90}deg`)
    }
    return (
        <div className="auth-login-frame" onPointerMove={followPointer}
            onPointerLeave={event => event.currentTarget.style.setProperty('--edge-proximity', '0')}>
            <span className="auth-login-edge-light" aria-hidden="true" />
            {children}
        </div>
    )
}
