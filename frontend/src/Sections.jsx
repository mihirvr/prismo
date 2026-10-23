import { useCallback, useEffect, useRef, useState } from 'react'
import { ITEMS } from './library.js'
import { Down, Folder, GitHub, Left, LinuxMark, Plus, Right, WindowsMark } from './icons.jsx'

const REPO = 'https://github.com/mihirvr/prismo'

// The installers' download links. While a link is null its button is greyed out ("soon").
// At the release, put the Releases page's file links here.
const DOWNLOADS = {
  windows: null,
  linux: null,
}

function Head({ title, intro, center, id }) {
  return (
    <div className={`shead${center ? ' center' : ''}`}>
      <h2 className="title rise" id={id}>{title}</h2>
      {intro && <p className="intro rise" style={{ '--i': 2 }}>{intro}</p>}
    </div>
  )
}

/* ---------- why Prismo: the Picasa gap ---------- */

function Story() {
  return (
    <section className="section container" id="why" aria-labelledby="why-h">
      <div className="story">
        <div>
          <Head id="why-h" title="Picasa did this. Then it was gone." />
          <div className="body">
            <p className="rise">Years ago, Google Picasa gave Windows a gallery that felt like a phone: every photo on your PC, by date, one scroll away. Google retired it in 2016.</p>
            <p className="muted rise" style={{ '--i': 1 }}>Since then, no well-known open-source app on Windows shows your photos that way. File Explorer makes you open folder after folder, go back, and try the next one.</p>
            <p className="rise" style={{ '--i': 2 }}><strong>Prismo sets out to fill that gap:</strong> a phone-style gallery for your PC that is open source, offline and fast.</p>
          </div>
        </div>
        <div className="compare">
          <figure className="rise" style={{ '--i': 1 }}>
            <figcaption>Today: folder by folder<span>Open, look, go back, repeat.</span></figcaption>
            <div className="mini" data-ui aria-hidden="true">
              <div className="path">This PC › D: › Photos › 2024 › Goa trip</div>
              <div className="rows">
                {[['DCIM', '412 items'], ['Camera', '96 items'], ['WhatsApp Images', '1,208 items'], ['Screenshots', '57 items'], ['Edited', '23 items']].map(([n, c]) => (
                  <div key={n}><Folder />{n}<small>{c}</small></div>
                ))}
              </div>
              <div className="mfoot">5 folders. The photos are somewhere inside.</div>
            </div>
          </figure>
          <figure className="rise" style={{ '--i': 2 }}>
            <figcaption>With Prismo: one timeline<span>Every folder and drive, by date.</span></figcaption>
            <div className="mini" data-ui aria-hidden="true">
              <div className="path">Timeline · 11 photos and videos</div>
              <div className="day">Saturday, 3 May</div>
              <div className="tiles">{ITEMS.slice(0, 8).map((it) => <img key={it.id} src={it.src} alt="" loading="lazy" />)}</div>
              <div className="day">Sunday, 27 April</div>
              <div className="tiles">{ITEMS.slice(8, 11).map((it) => <img key={it.id} src={it.src} alt="" loading="lazy" />)}</div>
            </div>
          </figure>
        </div>
      </div>
    </section>
  )
}

/* ---------- inside the app: cards in a row, the centred one is lit ---------- */

const CARDS = [
  {
    head: 'Search',
    name: 'Search that reads the whole library',
    text: 'Type a name, a folder, a camera, a date or a size. Results come from a local index, so they appear as you type.',
    body: (
      <>
        <div className="row2"><b>“x-t5 coast”</b><span>3 results</span></div>
        {[['IMG_2413.JPG', 'Holidays'], ['IMG_2426.JPG', 'Holidays'], ['IMG_2439.MP4', 'Camera']].map(([n, f]) => (
          <div className="row2" key={n}><span>{n}</span><span>{f}</span></div>
        ))}
        <p style={{ color: 'var(--muted)' }}>Filters: Photos, Videos, JPEG, Portrait, Screenshots…</p>
      </>
    ),
  },
  {
    head: 'Info',
    name: 'Details for every file',
    text: 'Camera settings and GPS position for a photo; formats and tracks for a video. Press I in the viewer.',
    body: (
      <>
        <div className="row2"><span>Camera</span><span>X-T5</span></div>
        <div className="row2"><span>Lens</span><span>23 mm f/2</span></div>
        <div className="row2"><span>Exposure</span><span>1/500 s, f/5.6, ISO 160</span></div>
        <div className="row2"><span>Size</span><span>7728 × 5152</span></div>
        <div className="row2"><span>Position</span><span>sample coordinates</span></div>
      </>
    ),
  },
  {
    head: 'Video',
    name: 'Videos remember where you stopped',
    text: 'MP4, MOV, MKV, WebM and AVI play inside the app, with speed, sound tracks and .srt subtitles. A thin bar on the thumbnail shows how far you watched.',
    body: (
      <>
        <div className="meter"><i /></div>
        <div className="row2"><span>0:14 / 0:38</span><span>Speed 1×</span></div>
        <div className="row2"><span>Sound</span><span>English (2 tracks)</span></div>
        <div className="row2"><span>Subtitles</span><span>clip.srt, delay +0.0 s</span></div>
      </>
    ),
  },
  {
    head: 'Library folders',
    name: 'A drive goes away, nothing is lost',
    text: 'An unplugged drive or a network share that is off shows as offline. Its tiles go grey, and everything comes back with the drive.',
    body: (
      <>
        <div className="row2"><span>Camera</span><span>ready</span></div>
        <div className="row2"><span>Holidays</span><span>ready</span></div>
        <div className="row2"><span>NAS · Family</span><span>can’t be reached</span></div>
        <p className="warnbox">Its files stay in your library and come back when it does.</p>
      </>
    ),
  },
  {
    head: 'Settings › Look',
    name: 'Light, Dark, AMOLED black, or follow Windows',
    text: 'The look can change with Windows, title bar included. Every setting is kept after a restart.',
    body: (
      <>
        <div className="sw"><i style={{ background: '#ffffff' }} /><i style={{ background: '#14172a' }} /><i style={{ background: '#000' }} /></div>
        <div className="row2"><span>Light</span><span>Dark · AMOLED</span></div>
        <div className="row2"><span>Follow system</span><span>on</span></div>
        <div className="row2"><span>Thumbnail cache</span><span>Clear thumbnails</span></div>
      </>
    ),
  },
]

// A centred stack: the active card in front, the next ones peeking out to the right behind it,
// smaller, fainter and blurred. Moving on brings the next card forward and sharpens it.
function Showcase() {
  const [at, setAt] = useState(0)
  const go = useCallback((i) => setAt(Math.max(0, Math.min(CARDS.length - 1, i))), [])
  const x0 = useRef(null)

  const onKey = (e) => {
    if (e.key === 'ArrowRight') { e.preventDefault(); go(at + 1) }
    if (e.key === 'ArrowLeft') { e.preventDefault(); go(at - 1) }
  }
  const down = (e) => { x0.current = e.clientX }
  const up = (e) => {
    if (x0.current == null) return
    const dx = e.clientX - x0.current
    x0.current = null
    if (dx < -40) go(at + 1)
    else if (dx > 40) go(at - 1)
  }

  return (
    <section className="section container" id="features" aria-labelledby="feat-h">
      <div className="show snap-block">
        <Head id="feat-h" title="Everything a gallery should do." intro="Search, details, videos, offline drives and looks. Use the arrows, swipe, or click the card behind." center />
        <div className="stack rise" style={{ '--i': 2 }} tabIndex={0} onKeyDown={onKey} onPointerDown={down} onPointerUp={up} onPointerCancel={() => (x0.current = null)} aria-roledescription="carousel" aria-label="App features">
          {CARDS.map((c, i) => {
            const k = i - at
            return (
              <div
                className="card"
                key={c.head}
                data-k={k < 0 ? 'gone' : k > 3 ? 'far' : k}
                style={{ '--k': Math.max(0, k) }}
                onClick={() => k > 0 && go(i)}
                aria-hidden={k !== 0}
              >
                <div className="ph">{c.head}</div>
                <div className="pb">{c.body}</div>
              </div>
            )
          })}
        </div>
        <div className="cap" aria-live="polite">
          <h3 key={at}>{CARDS[at].name}</h3>
          <p>{CARDS[at].text}</p>
        </div>
        <div className="dots">
          {CARDS.map((c, i) => (
            <button key={c.head} aria-label={`Show ${c.head}`} aria-current={at === i} onClick={() => go(i)} />
          ))}
        </div>
        <div className="arrows">
          <button className="arrow" onClick={() => go(at - 1)} disabled={at === 0} aria-label="Previous card"><Left /></button>
          <button className="arrow" onClick={() => go(at + 1)} disabled={at === CARDS.length - 1} aria-label="Next card"><Right /></button>
        </div>
      </div>
    </section>
  )
}

/* ---------- principles: one settings panel, a note beside each row ---------- */

const PRINCIPLES = [
  { k: 'offline', name: 'Offline first', text: 'No account, no cloud, no tracking. It works with the network cable pulled.', row: ['Work offline', 'on', true] },
  { k: 'files', name: 'Your files stay yours', text: 'Prismo never moves, renames, re-encodes or deletes a file by itself. You do that, on purpose.', row: ['Change files on its own', 'never', false] },
  { k: 'cache', name: 'Cache first, so it is fast', text: 'Thumbnails are made once and kept. Only what is on screen is decoded, so big libraries scroll smoothly.', row: ['Thumbnail cache', 'kept', true] },
  { k: 'places', name: 'Drives and NAS', text: 'Internal disks, USB drives and network shares in one library. A missing one shows as offline, not deleted.', row: ['Library folders', '3 places', true] },
  { k: 'private', name: 'Private by default', text: 'Everything runs on your PC. Nothing is uploaded and nothing is counted.', row: ['Send anything online', 'never', false] },
  { k: 'open', name: 'Open source', text: 'MIT licence. Read the code, build it yourself, change it, report a problem.', row: ['Source code', 'MIT', true] },
]

function Principles() {
  return (
    <section className="section container" aria-labelledby="pr-h">
      <div>
        <Head id="pr-h" title="Quiet by design." intro="Six promises the app keeps, each one a switch that stays where it is." />
        <div className="pgrid">
          {PRINCIPLES.map((f, i) => (
            <div className="pcard rise" style={{ '--i': i % 3 }} key={f.k}>
              <div className="set" data-ui aria-hidden="true">
                <span>{f.row[0]}<small>{f.row[1]}</small></span>
                <span className={`tog${f.row[2] ? ' on' : ''}`} />
              </div>
              <b>{f.name}</b>
              <p>{f.text}</p>
            </div>
          ))}
        </div>
      </div>
    </section>
  )
}

/* ---------- how it works: the window holds still while the steps sharpen past it ---------- */

const STEPS = [
  { h: 'Point it at a folder.', p: 'A folder, a USB drive or a network share. Nothing is imported or copied; your files stay put.' },
  { h: 'It reads and remembers.', p: 'Dates and camera data are read once, in the background, and thumbnails are cached. You can browse while it works.' },
  { h: 'Then browse like a phone.', p: 'One timeline by day, search, filters, favorites, hidden and trash. New files appear by themselves within a second or two.' },
]

function PinWindow({ on }) {
  return (
    <div className="window" data-ui>
      <div className="titlebar"><img src="/brand/prismo-mark.png" alt="" /><b>{['Library folders', 'Indexing', 'Timeline'][on]}</b></div>
      <div className="fill" key={on}>
        {on === 0 && (
          <div className="prog">
            {[['Pictures', 'C:', 'on'], ['Camera', 'D:', 'on'], ['Family', 'NAS', 'adding…']].map(([f, d, st], i) => (
              <div className="line" key={f} style={{ opacity: i === 2 ? 0.6 : 1 }}><span><Folder />{f}<small>{d}</small></span><span>{st}</span></div>
            ))}
            <span className="addbtn">Add folder…</span>
            <p className="note">Nothing is imported or copied. Prismo only reads.</p>
          </div>
        )}
        {on === 1 && (
          <div className="prog">
            <div className="line" style={{ borderBottom: 0 }}><span>Reading metadata</span><span>{ITEMS.length} of {ITEMS.length}</span></div>
            <div className="bartrack"><i /></div>
            <div className="line" style={{ borderBottom: 0 }}><span>Making thumbnails</span><span>cached</span></div>
            <div className="bartrack"><i style={{ animationDelay: '0.35s' }} /></div>
            <div className="thumbs">{ITEMS.slice(0, 12).map((it, k) => <img key={it.id} src={it.src} alt="" style={{ '--k': k }} />)}</div>
          </div>
        )}
        {on === 2 && (
          <div className="grid" style={{ padding: 12, width: '100%', alignSelf: 'start', gridTemplateColumns: 'repeat(auto-fill, minmax(120px, 1fr))' }}>
            {ITEMS.slice(0, 11).map((it) => (
              <div className="tile" key={it.id}><img src={it.src} alt="" loading="lazy" /></div>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}

function How() {
  const [on, setOn] = useState(0)
  const stepsRef = useRef(null)
  const refs = useRef([])

  // One rule drives everything: the rail fills to the middle of the screen, and a step becomes the active one
  // (window state, sharp text) exactly when the fill reaches its dot. Each step stays sharp from its own dot
  // to the next step's dot, and blurs outside that stretch.
  useEffect(() => {
    const still = matchMedia('(prefers-reduced-motion: reduce)').matches
    let raf = 0, current = 0
    const update = () => {
      raf = 0
      const vh = innerHeight, mid = vh / 2
      const els = refs.current.filter(Boolean)
      const dots = els.map((el) => { const r = el.getBoundingClientRect(); return r.top + r.height / 2 })
      let active = 0
      dots.forEach((y, i) => { if (y <= mid) active = i })
      if (active !== current) { current = active; setOn(active) }
      if (!still) {
        els.forEach((el, i) => {
          const start = i === 0 ? -Infinity : dots[i]
          const end = i === dots.length - 1 ? Infinity : dots[i + 1]
          const gap = mid < start ? start - mid : mid > end ? mid - end : 0
          el.style.setProperty('--d', Math.min(1, Math.max(0, gap - vh * 0.04) / (vh * 0.22)).toFixed(3))
        })
      }
      // the rail runs from the first dot to the last, so the fill touches each dot as its step begins
      const s = stepsRef.current
      const rail = s?.querySelector('.rail')
      if (rail && dots.length) {
        const box = s.getBoundingClientRect()
        rail.style.top = `${dots[0] - box.top}px`
        rail.style.bottom = `${box.bottom - dots[dots.length - 1]}px`
        const span = dots[dots.length - 1] - dots[0]
        s.style.setProperty('--p', Math.max(0, Math.min(1, (mid - dots[0]) / span)).toFixed(3))
      }
    }
    const onScroll = () => { if (!raf) raf = requestAnimationFrame(update) }
    update()
    addEventListener('scroll', onScroll, { passive: true })
    addEventListener('resize', onScroll)
    return () => { removeEventListener('scroll', onScroll); removeEventListener('resize', onScroll); cancelAnimationFrame(raf) }
  }, [])

  return (
    <section className="section container" id="how" aria-labelledby="how-h">
      <div>
        <div className="how snap-block" data-snap="top">
          <div>
            <Head id="how-h" title="Set it up once. Then just browse." />
            <div className="steps" ref={stepsRef}>
              <div className="rail" aria-hidden="true"><i /></div>
              {STEPS.map((s, i) => (
                <div className="step" key={s.h} data-i={i} data-on={on === i} ref={(el) => (refs.current[i] = el)}>
                  <h3>{s.h}</h3>
                  <p>{s.p}</p>
                  {i === 0 && <span className={`hint${on > 0 ? ' gone' : ''}`}><Down />Keep scrolling for the next step</span>}
                  <div className="pin-inline" aria-hidden="true"><PinWindow on={i} /></div>
                </div>
              ))}
            </div>
          </div>
          <div className="pin" aria-hidden="true"><PinWindow on={on} /></div>
        </div>
      </div>
    </section>
  )
}

/* ---------- built on ---------- */

function Built() {
  return (
    <section className="section container" aria-label="Built on open source">
      <div className="built rise">
        <p>Built on open source</p>
        <ul>{['Java', 'JavaFX', 'SQLite', 'FFmpeg', 'metadata-extractor', 'TwelveMonkeys'].map((n) => <li key={n}>{n}</li>)}</ul>
      </div>
    </section>
  )
}

/* ---------- questions ---------- */

const FAQ = [
  ['Is it free?', 'Yes. Prismo is free and open source under the MIT licence. No account, no subscription.'],
  ['Isn’t this what Picasa did?', 'In spirit, yes. Google retired Picasa in 2016. Prismo is a new, open-source take on the same idea: a phone-style gallery for the photos already on your PC.'],
  ['Does it copy or move my photos?', 'No. It reads them where they are. A file changes only when you rename, move or delete it yourself.'],
  ['Does anything go online?', 'No. There is no cloud, no upload and no telemetry. It works with no internet at all.'],
  ['What about NAS and USB drives?', 'Add them as library folders. If one can’t be reached, its tiles go grey and a banner says so, and everything returns with it. Network folders are checked every 10 minutes instead of watched live.'],
  ['Which files does it open?', 'JPEG, PNG, WebP, GIF, HEIC, and RAW files (CR2, CR3, NEF, ARW, DNG and more) through the preview inside them. Videos: MP4, MOV, MKV, WebM and AVI.'],
  ['Does it run on Linux or Mac?', 'Linux: yes, as a .deb package for Ubuntu, Debian and their relatives. Prismo is built and tested on Windows first; the Linux package is checked automatically (it installs and opens), but has not been used much on a real Linux desktop yet, so expect some rough edges. Mac: not yet.'],
  ['When can I download it?', 'Soon. The installer is the next thing being built, and the Download button turns on at the first release. Until then you can build it from the source on GitHub.'],
]

function Faq() {
  const [sel, setSel] = useState(0)
  return (
    <section className="section container" id="faq" aria-labelledby="faq-h">
      <div>
        <div className="faq">
          <div className="faq-side">
            <Head id="faq-h" title="Good questions." intro="What people ask first. Open one to read the answer." />
            <div className="ask rise" style={{ '--i': 3 }}>
              <p><b>Didn’t find yours?</b> Ask on GitHub. Questions become answers here.</p>
              <a className="btn ghost small" href={`${REPO}/issues`}><GitHub />Ask on GitHub</a>
            </div>
          </div>
          <div className="acc">
            {FAQ.map(([q, a], i) => (
              <div className="item rise" style={{ '--i': i % 4 }} data-open={sel === i} key={q}>
                <button className="q" aria-expanded={sel === i} aria-controls={`faq-${i}`} onClick={() => setSel(sel === i ? -1 : i)}>
                  {q}<Plus />
                </button>
                <div className="a" id={`faq-${i}`} role="region"><div><p>{a}</p></div></div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </section>
  )
}

/* ---------- download ---------- */

function DownloadButton({ href, icon, label }) {
  if (href) return <a className="btn" href={href}>{icon}{label}</a>
  return <a className="btn" href="#download" aria-disabled="true" onClick={(e) => e.preventDefault()}>{icon}{label} (soon)</a>
}

function Download() {
  return (
    <section className="section container" id="download" aria-labelledby="dl-h">
      <div className="glass cta">
        <img className="mark rise" src="/brand/prismo-mark.png" alt="The Prismo logo" width="128" height="128" />
        <h2 id="dl-h" className="title rise" style={{ '--i': 1 }}>Get Prismo for your PC.</h2>
        <p className="intro rise" style={{ '--i': 2 }}>Free and open source, for Windows 10 / 11 and Linux (Ubuntu, Debian). The installers arrive with the first release; until then you can build it from the source.</p>
        <div className="row rise" style={{ '--i': 3 }}>
          <DownloadButton href={DOWNLOADS.windows} icon={<WindowsMark />} label="Download for Windows" />
          <DownloadButton href={DOWNLOADS.linux} icon={<LinuxMark />} label="Download for Linux" />
          <a className="btn ghost" href={REPO}><GitHub />View on GitHub</a>
        </div>
      </div>
    </section>
  )
}

export function Sections() {
  return (
    <>
      <Story />
      <Showcase />
      <Principles />
      <How />
      <Built />
      <Faq />
      <Download />
    </>
  )
}
