import { lazy, Suspense, useCallback, useEffect, useState } from 'react'
import AppWindow from './AppWindow.jsx'
import PhotoFan from './PhotoFan.jsx'
import { Sections } from './Sections.jsx'
import { useTheme } from './theme.js'
import { Down, GitHub, Moon, Sun } from './icons.jsx'
import still from './fallback.svg'

const Gradient = lazy(() => import('./Gradient.jsx'))

export const REPO = 'https://github.com/mihirvr/prismo'

// The moving gradient is skipped on phones, for reduced motion, on data saver and without WebGL;
// those visitors get the still picture of it.
function canAnimate() {
  if (matchMedia('(prefers-reduced-motion: reduce)').matches) return false
  if (matchMedia('(max-width: 860px)').matches) return false
  if (navigator.connection?.saveData) return false
  try {
    const c = document.createElement('canvas')
    return !!(c.getContext('webgl2') || c.getContext('webgl'))
  } catch {
    return false
  }
}

function useReveal() {
  useEffect(() => {
    const els = document.querySelectorAll('.rise')
    if (!('IntersectionObserver' in window)) { els.forEach((e) => e.classList.add('in')); return }
    const io = new IntersectionObserver((entries) => {
      for (const e of entries) if (e.isIntersecting) { e.target.classList.add('in'); io.unobserve(e.target) }
    }, { rootMargin: '0px 0px -6% 0px' })
    els.forEach((e) => io.observe(e))
    return () => io.disconnect()
  }, [])
}

// Set while a snap glides the page, so the bar stays hidden even when the glide goes up.
let snapping = false

// The bar slides away while you scroll down and comes back as soon as you scroll up.
function useScrollState() {
  const [state, setState] = useState({ barHidden: false, scrolled: false })
  useEffect(() => {
    let last = scrollY, hidden = false, raf = 0
    const update = () => {
      raf = 0
      const y = scrollY
      if (y < 80) hidden = false
      else if (snapping) hidden = true
      else if (y > last + 6) hidden = true
      else if (y < last - 6) hidden = false
      last = y
      const barHidden = hidden, scrolled = y > 120
      setState((s) => (barHidden === s.barHidden && scrolled === s.scrolled ? s : { barHidden, scrolled }))
    }
    const onScroll = () => { if (!raf) raf = requestAnimationFrame(update) }
    addEventListener('scroll', onScroll, { passive: true })
    return () => { removeEventListener('scroll', onScroll); cancelAnimationFrame(raf) }
  }, [])
  return state
}

// When scrolling stops within about half a screen of a snap block, glide so the block is centred
// (or top-aligned if it is taller than the screen). Desktop only; nothing for reduced motion.
function useSnap() {
  useEffect(() => {
    if (matchMedia('(prefers-reduced-motion: reduce)').matches) return
    let gliding = false, timer = 0
    const settle = () => {
      if (gliding) { gliding = false; snapping = false; return }
      if (innerWidth < 861) return
      const vh = innerHeight
      let best = null
      for (const el of document.querySelectorAll('.snap-block')) {
        const r = el.getBoundingClientRect()
        // data-snap="top": settle on the block's start (How it works); otherwise centre it, or top-align it if it is taller than the screen
        const target = el.dataset.snap === 'top' ? scrollY + r.top - 40
          : r.height > vh - 40 ? scrollY + r.top - 20 : scrollY + r.top + r.height / 2 - vh / 2
        const dist = Math.abs(target - scrollY)
        // a start snap only reaches a little way past the start, so stopping between the steps is left alone
        if (el.dataset.snap === 'top' && scrollY - target > vh * 0.1) continue
        if (dist > 4 && dist < vh * 0.45 && (!best || dist < best.dist)) best = { target, dist }
      }
      if (best) { gliding = true; snapping = true; scrollTo({ top: best.target, behavior: 'smooth' }) }
    }
    const hasEnd = 'onscrollend' in window
    const onScroll = () => { clearTimeout(timer); timer = setTimeout(settle, 160) }
    if (hasEnd) addEventListener('scrollend', settle)
    else addEventListener('scroll', onScroll, { passive: true })
    return () => { removeEventListener('scrollend', settle); removeEventListener('scroll', onScroll); clearTimeout(timer) }
  }, [])
}

export default function App() {
  const [theme, toggle] = useTheme()
  const [animate] = useState(canAnimate)
  const [ready, setReady] = useState(false)
  const onReady = useCallback(() => setReady(true), [])
  const { barHidden, scrolled } = useScrollState()
  useReveal()
  useSnap()

  const toTop = (e) => {
    e.preventDefault()
    scrollTo({ top: 0, behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' })
  }

  return (
    <>
      <div className="bg" aria-hidden="true">
        {/* the still stays underneath, so nothing dark ever shows while the moving one starts */}
        <img className="still" src={still} alt="" />
        {animate && (
          <div className={`gl${ready ? ' on' : ''}`}>
            <Suspense fallback={null}>
              <Gradient theme={theme} onReady={onReady} />
            </Suspense>
          </div>
        )}
        <div className="veil" />
        <div className="grain" />
      </div>

      <a className={`logo${barHidden ? ' alone' : ''}`} href="#top" onClick={toTop} aria-label="Prismo, back to the top">
        <img src="/brand/prismo-mark.png" alt="" width="34" height="34" />
      </a>

      <header className={`nav${barHidden ? ' up' : ''}`}>
        <div className="container">
          <span className="logo-space" />
          <a className="link hide-sm" href="#why">Why Prismo</a>
          <a className="link hide-sm" href="#features">Features</a>
          <a className="link hide-sm" href="#how">How it works</a>
          <a className="link hide-sm" href="#faq">Questions</a>
          <a className="link hide-sm" href={REPO}>GitHub</a>
          <button className="theme-btn" onClick={toggle} aria-label={`Switch to ${theme === 'dark' ? 'light' : 'dark'} mode`}>
            {theme === 'dark' ? <Sun /> : <Moon />}
            <span className="hide-sm">{theme === 'dark' ? 'Light' : 'Dark'}</span>
          </button>
          <a className="btn small" href="#download">Download</a>
        </div>
      </header>

      <main id="top">
        <section className="hero container" aria-labelledby="h1">
          <div className="hero-copy">
            <h1 id="h1" className="rise">Your whole PC, like a phone gallery.</h1>
            <p className="sub rise" style={{ '--i': 1 }}>
              Prismo puts every photo and video on your drives into one timeline, sorted by date, the way your phone does. No more opening folder after folder. It works offline and never copies, moves or uploads a file.
            </p>
            <div className="actions rise" style={{ '--i': 2 }}>
              <a className="btn" href="#download">Download for PC</a>
              <a className="btn ghost" href={REPO}><GitHub />View on GitHub</a>
            </div>
            <p className="note rise" style={{ '--i': 3 }}>Free and open source (MIT). No account, no tracking.</p>
          </div>
          <div className="rise" style={{ '--i': 2 }}><PhotoFan /></div>
        </section>

        <section className="try container" id="try" aria-labelledby="try-h">
          <div className="snap-block">
            <h2 id="try-h" className="title rise">Try it right here.</h2>
            <div className="demo glass demo-frame rise" style={{ '--i': 2 }}>
              <AppWindow />
            </div>
          </div>
        </section>

        <Sections />
      </main>

      <a className={`scroll-hint${scrolled ? ' gone' : ''}`} href="#try" aria-label="Scroll down">
        Scroll <Down />
      </a>

      <footer className="foot">
        <div className="container">
          <div className="who">
            <img src="/brand/prismo-mark.png" alt="" width="26" height="26" />
            <b>Prismo</b>
            <span>A free photo library for Windows and Linux</span>
          </div>
          <nav aria-label="Footer">
            <a href="#why">Why Prismo</a>
            <a href="#features">Features</a>
            <a href="#how">How it works</a>
            <a href="#faq">Questions</a>
            <a href={REPO}>GitHub</a>
          </nav>
          <span className="right">MIT licence · 2026</span>
        </div>
      </footer>
    </>
  )
}
