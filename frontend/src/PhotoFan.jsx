import { useEffect, useRef } from 'react'
import { ITEMS } from './library.js'

// A loose cluster of sample photos floating at different depths. It tilts toward the pointer and each
// photo drifts by its depth, so the nearer ones move more. Without a pointer (phones) it only floats.
const CARDS = [
  { i: 4, x: 2, y: 8, w: 40, r: -7, d: 0.35 },
  { i: 1, x: 56, y: 0, w: 36, r: 6, d: 0.55 },
  { i: 9, x: 66, y: 46, w: 32, r: 8, d: 0.45 },
  { i: 5, x: 0, y: 56, w: 34, r: 5, d: 0.6 },
  { i: 0, x: 26, y: 26, w: 48, r: -2, d: 1, badge: 'Saturday, 3 May' },
  { i: 3, x: 40, y: 68, w: 30, r: -6, d: 0.8, video: '0:35' },
  // the app's own icon, floating with the photos just above the date
  { logo: true, x: 42, y: -4, w: 15, r: -5, d: 0.9 },
]

export default function PhotoFan() {
  const box = useRef(null)

  useEffect(() => {
    const el = box.current
    if (!el || matchMedia('(prefers-reduced-motion: reduce)').matches) return
    let tx = 0, ty = 0, x = 0, y = 0, raf = 0
    const tick = () => {
      x += (tx - x) * 0.06
      y += (ty - y) * 0.06
      el.style.setProperty('--mx', x.toFixed(4))
      el.style.setProperty('--my', y.toFixed(4))
      raf = Math.abs(tx - x) + Math.abs(ty - y) > 0.0005 ? requestAnimationFrame(tick) : 0
    }
    const onMove = (e) => {
      tx = (e.clientX / innerWidth) * 2 - 1
      ty = (e.clientY / innerHeight) * 2 - 1
      if (!raf) raf = requestAnimationFrame(tick)
    }
    addEventListener('pointermove', onMove, { passive: true })
    return () => { removeEventListener('pointermove', onMove); cancelAnimationFrame(raf) }
  }, [])

  return (
    <div className="fan" ref={box} aria-hidden="true">
      <div className="fan-in">
        {CARDS.map((c, k) => (
          <figure
            key={c.logo ? 'logo' : c.i}
            className={`ph${c.logo ? ' logo-card' : ''}`}
            style={{ left: `${c.x}%`, top: `${c.y}%`, width: `${c.w}%`, '--r': `${c.r}deg`, '--d': c.d, '--k': k }}
          >
            <img src={c.logo ? '/brand/prismo-mark.png' : ITEMS[c.i].src} alt="" />
            {c.video && <span className="vb">▶ {c.video}</span>}
            {c.badge && <span className="day">{c.badge}</span>}
          </figure>
        ))}
      </div>
    </div>
  )
}
