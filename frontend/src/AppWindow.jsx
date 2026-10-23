import { useEffect, useMemo, useRef, useState } from 'react'
import { ITEMS, FOLDERS } from './library.js'
import { MousePointerClick } from 'lucide-react'
import { Search, Star } from './icons.jsx'

const NAS = 'NAS · Family'

// A working piece of the real app, built in HTML: Timeline, folders, search, filters,
// an unplugged drive, the viewer with its Info panel. Sample pictures only.
export default function AppWindow({ stageLabel = 'Prismo' }) {
  const [folder, setFolder] = useState(null)
  const [kind, setKind] = useState('all')
  const [q, setQ] = useState('')
  const [nasOff, setNasOff] = useState(false)
  const [open, setOpen] = useState(null)
  const [info, setInfo] = useState(false)
  const [narrow] = useState(() => matchMedia("(max-width: 860px)").matches) // phones get a shorter search hint
  const [touched, setTouched] = useState(false) // the pulsing hint goes away after the first click
  const root = useRef(null)

  const visible = useMemo(() => {
    const words = q.trim().toLowerCase().split(/\s+/).filter(Boolean)
    return ITEMS.filter((it) => {
      if (folder && it.folder !== folder) return false
      if (kind === 'photos' && it.video) return false
      if (kind === 'videos' && !it.video) return false
      const hay = `${it.title} ${it.folder} ${it.camera} ${it.name}`.toLowerCase()
      return words.every((w) => hay.includes(w))
    })
  }, [folder, kind, q])

  const days = useMemo(() => {
    const out = []
    for (const it of visible) {
      let g = out.find((d) => d.day === it.day)
      if (!g) out.push((g = { day: it.day, items: [] }))
      g.items.push(it)
    }
    return out
  }, [visible])

  const count = (f) => ITEMS.filter((it) => it.folder === f).length
  const idx = open == null ? -1 : visible.findIndex((v) => v.id === open)
  const go = (d) => {
    if (!visible.length) return
    const n = (idx + d + visible.length) % visible.length
    setOpen(visible[n].id)
  }

  useEffect(() => {
    if (open == null) return
    const onKey = (e) => {
      if (!root.current?.contains(document.activeElement) && document.activeElement !== document.body) return
      if (e.key === 'Escape') { if (info) setInfo(false); else setOpen(null) }
      else if (e.key === 'ArrowRight' || e.key === 'PageDown') go(1)
      else if (e.key === 'ArrowLeft' || e.key === 'PageUp') go(-1)
      else if (e.key.toLowerCase() === 'i') setInfo((v) => !v)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })

  const cur = open == null ? null : ITEMS[open]

  return (
    <div className="window" data-ui ref={root} onPointerDown={() => setTouched(true)} onKeyDown={() => setTouched(true)} style={{ position: 'relative' }} role="group" aria-label="Prismo, working demo with sample pictures">
      <div className="titlebar">
        <img src="/brand/prismo-mark.png" alt="" />
        <b>{stageLabel}</b>
        <span className="spacer" />
        <label className="search">
          <Search />
          <input
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder={narrow ? "Search… try “fox”" : "Search name, folder, camera… try “fox”"}
            aria-label="Search the sample library"
          />
        </label>
      </div>
      <div className="winbody">
        <nav className="side" aria-label="Collections">
          <button aria-pressed={folder === null} onClick={() => setFolder(null)}>
            Timeline <span className="count">{ITEMS.length}</span>
          </button>
          <h3>Folders</h3>
          {FOLDERS.map((f) => (
            <button key={f} aria-pressed={folder === f} onClick={() => setFolder(f)} className={f === NAS && nasOff ? 'off' : ''}>
              {f}
              <span className="count">{f === NAS && nasOff ? 'offline' : count(f)}</span>
            </button>
          ))}
        </nav>
        <div className="main">
          <div className="tools">
            {['all', 'photos', 'videos'].map((k) => (
              <button key={k} className="chip" aria-pressed={kind === k} onClick={() => setKind(k)}>
                {k[0].toUpperCase() + k.slice(1)}
              </button>
            ))}
            <button className="chip warn push" aria-pressed={nasOff} onClick={() => setNasOff((v) => !v)}>
              {nasOff ? 'Plug the NAS back in' : 'Unplug the NAS'}
            </button>
          </div>
          {nasOff && (
            <div className="banner" role="status">
              {NAS} can’t be reached. Nothing is lost; its pictures come back when it does.
            </div>
          )}
          <div className="scroll">
            {days.length === 0 && <p className="empty">Nothing matches. Clear the search or the filter.</p>}
            {days.map((d) => (
              <section key={d.day}>
                <h4>{d.day}</h4>
                <div className="grid">
                  {d.items.map((it) => (
                    <button
                      key={it.id}
                      className="tile"
                      data-off={nasOff && it.folder === NAS}
                      onClick={() => { setOpen(it.id); setInfo(false) }}
                      aria-label={`${it.title}${it.video ? ', video' : ''}`}
                    >
                      <img src={it.src} alt="" loading="lazy" width="236" height="236" />
                      {it.fav && <span className="fav"><Star /></span>}
                      {it.video && <span className="badge">▶ {it.dur}</span>}
                      {it.video && it.progress > 0 && (
                        <span className="bar"><i className={it.progress >= 1 ? 'done' : ''} style={{ width: `${it.progress * 100}%` }} /></span>
                      )}
                    </button>
                  ))}
                </div>
              </section>
            ))}
          </div>
        </div>
      </div>
      {!touched && <span className="click-hint" aria-hidden="true"><MousePointerClick strokeWidth={2.2} /></span>}
      {cur && (
        <div className="viewer" role="dialog" aria-label={cur.title}>
          <div className="pic">
            <img src={cur.src} alt={cur.title} />
            <button className="nbtn prev" onClick={() => go(-1)} aria-label="Previous">←</button>
            <button className="nbtn next" onClick={() => go(1)} aria-label="Next">→</button>
            <div className="vbar">
              <button onClick={() => setInfo((v) => !v)} aria-pressed={info}>Info (I)</button>
              <button onClick={() => setOpen(null)}>Close (Esc)</button>
            </div>
          </div>
          {info && (
            <aside className="info" aria-label="Info">
              <b>{cur.name}</b>
              <dl>
                <dt>Folder</dt><dd>{cur.folder}</dd>
                <dt>Taken</dt><dd>{cur.day}</dd>
                <dt>Camera</dt><dd>{cur.camera} (sample)</dd>
                <dt>Size</dt><dd>{cur.size}</dd>
                <dt>Kind</dt><dd>{cur.video ? 'Video' : 'Photo'}</dd>
              </dl>
            </aside>
          )}
        </div>
      )}
    </div>
  )
}
