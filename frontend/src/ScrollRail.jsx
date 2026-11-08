import { useEffect, useRef } from 'react'

// Our own page scroll bar: a slim pill in the logo's colours floating over the background, with no grey gutter
// (a browser paints its scroll bar's track in the page colour, so the moving background could never show behind it).
// The page's own bar is hidden in styles.css; scrolling itself stays the browser's (wheel, keys, touch, find).
// Drag the pill, or click the rail to jump there. Only for mouse users: phones and tablets show no bar anyway.
export default function ScrollRail() {
  const rail = useRef(null)
  const thumb = useRef(null)

  useEffect(() => {
    const r = rail.current, t = thumb.current
    let raf = 0, idle = 0, drag = null
    const measure = () => {
      const doc = document.documentElement
      const view = innerHeight, full = doc.scrollHeight, track = r.clientHeight
      const size = Math.max(40, Math.round((view / full) * track))
      const room = full - view
      const top = room > 0 ? (scrollY / room) * (track - size) : 0
      t.style.height = `${size}px`
      t.style.transform = `translateY(${top}px)`
      r.style.display = room > 4 ? '' : 'none'
      return { room, track, size }
    }
    const show = () => {
      r.classList.add('on')
      clearTimeout(idle)
      idle = setTimeout(() => { if (!drag) r.classList.remove('on') }, 1200)
    }
    const onScroll = () => { show(); if (!raf) raf = requestAnimationFrame(() => { raf = 0; measure() }) }
    const onDown = (e) => {
      if (e.button !== 0) return
      e.preventDefault()
      const m = measure()
      if (e.target === t) {
        drag = { y: e.clientY, from: scrollY, ratio: m.room / Math.max(1, m.track - m.size) }
        t.setPointerCapture(e.pointerId)
        r.classList.add('drag', 'on')
      } else {
        // a click on the rail: put the pill's middle there
        const box = r.getBoundingClientRect()
        const at = (e.clientY - box.top - m.size / 2) / Math.max(1, m.track - m.size)
        scrollTo({ top: Math.max(0, Math.min(1, at)) * m.room, behavior: 'smooth' })
      }
    }
    const onMove = (e) => {
      if (!drag) return
      scrollTo({ top: drag.from + (e.clientY - drag.y) * drag.ratio, behavior: 'instant' })
    }
    const onUp = () => { if (drag) { drag = null; r.classList.remove('drag'); show() } }

    measure()
    addEventListener('scroll', onScroll, { passive: true })
    addEventListener('resize', onScroll)
    const grow = new ResizeObserver(onScroll) // the page grows as pictures load and sections open
    grow.observe(document.body)
    r.addEventListener('pointerdown', onDown)
    t.addEventListener('pointermove', onMove)
    t.addEventListener('pointerup', onUp)
    t.addEventListener('pointercancel', onUp)
    return () => {
      removeEventListener('scroll', onScroll)
      removeEventListener('resize', onScroll)
      grow.disconnect()
      r.removeEventListener('pointerdown', onDown)
      cancelAnimationFrame(raf)
      clearTimeout(idle)
    }
  }, [])

  return (
    <div className="scrollrail" ref={rail} aria-hidden="true">
      <div className="scrollthumb" ref={thumb} />
    </div>
  )
}
