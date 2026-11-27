import { useCallback, useEffect, useRef, useState } from 'react'
import { ITEMS } from './library.js'
import { Check, Copy, Down, Folder, GitHub, Left, LinuxMark, Plus, Right, WindowsMark } from './icons.jsx'

const REPO = 'https://github.com/mihirvr/prismo'

// The installers' download links. While a link is null its button is greyed out ("soon").
// At the release, put the Releases page's file links here.
export const DOWNLOADS = {
  windows: 'https://github.com/mihirvr/prismo/releases/download/v2.0.0/Prismo-2.0.0.exe',
  linux: 'https://github.com/mihirvr/prismo/releases/download/v2.0.0/prismo_2.0.0_amd64.deb',
}

function Head({ title, intro, center, id }) {
  return (
    <div className={`shead${center ? ' center' : ''}`}>
      <h2 className="title rise" id={id}>{title}</h2>
      {intro && <p className="intro rise" style={{ '--i': 2 }}>{intro}</p>}
    </div>
  )
}

/* ---------- the two windows beside the story play on their own while on screen ---------- */

// True while the element is at least partly on screen, and never when the visitor asked for less motion.
function useLive(ref) {
  const [live, setLive] = useState(false)
  useEffect(() => {
    const el = ref.current
    if (!el || matchMedia('(prefers-reduced-motion: reduce)').matches) return
    const io = new IntersectionObserver(([e]) => setLive(e.isIntersecting), { threshold: 0.3 })
    io.observe(el)
    return () => io.disconnect()
  }, [ref])
  return live
}

const ROOT = [['DCIM', '412 items'], ['Camera', '96 items'], ['WhatsApp Images', '1,208 items'], ['Screenshots', '57 items'], ['Edited', '23 items']]
const INSIDE = {
  DCIM: [['100ANDRO', '38 items'], ['Camera', '214 items'], ['.thumbnails', '160 items']],
  Camera: [['2024', '51 items'], ['2025', '45 items']],
  'WhatsApp Images': [['Sent', '903 items'], ['Private', '305 items']],
}
// Today: point at a folder, open it, find only more folders, go back, try the next one, and so on.
const EXPLORER_STEPS = [0, 1, 2].flatMap((i) => [{ hi: i }, { hi: i }, { open: i }, { open: i }])

function LiveExplorer() {
  const ref = useRef(null)
  const live = useLive(ref)
  const [t, setT] = useState(0)
  useEffect(() => {
    if (!live) return
    const id = setInterval(() => setT((n) => n + 1), 1100)
    return () => clearInterval(id)
  }, [live])
  // still (off screen, or less motion): the folder list with one row picked
  const f = live ? EXPLORER_STEPS[t % EXPLORER_STEPS.length] : { hi: 2 }
  const name = f.open != null ? ROOT[f.open][0] : null
  const rows = name ? INSIDE[name] : ROOT
  return (
    <div className="mini" data-ui aria-hidden="true" ref={ref}>
      <div className="path">This PC › D: › Photos › 2024 › Goa trip{name && ` › ${name}`}</div>
      <div className="rows" key={name || 'root'}>
        {rows.map(([n, c], i) => (
          <div key={n} className={!name && f.hi === i ? 'hi' : undefined}><Folder />{n}<small>{c}</small></div>
        ))}
      </div>
      <div className="mfoot">{name ? `${rows.length} more folders inside. Back, then the next one.` : '5 folders. The photos are somewhere inside.'}</div>
    </div>
  )
}

// With Prismo: one timeline that just scrolls, with the day staying on top.
const TIMELINE = [['Saturday, 3 May', [0, 1, 2, 3, 4, 5, 6, 7]], ['Sunday, 27 April', [8, 9, 10]], ['Friday, 11 April', [3, 6, 9, 1]], ['Saturday, 29 March', [2, 5, 8, 0, 7]], ['Sunday, 16 March', [10, 4, 1]]]

function LiveTimeline() {
  const ref = useRef(null)
  const box = useRef(null)
  const live = useLive(ref)
  useEffect(() => {
    const el = box.current
    if (!el) return
    if (!live) { el.scrollTop = 0; return }
    const id = setInterval(() => {
      const row = (el.querySelector('img')?.offsetHeight || 57) + 3
      const end = el.scrollHeight - el.clientHeight
      el.scrollTo({ top: el.scrollTop >= end - 2 ? 0 : Math.min(end, el.scrollTop + row), behavior: 'smooth' })
    }, 1600)
    return () => clearInterval(id)
  }, [live])
  return (
    <div className="mini" data-ui aria-hidden="true" ref={ref}>
      <div className="path">Grid › Timeline</div>
      <div className="tl" ref={box}>
        {TIMELINE.map(([day, ids]) => (
          <section key={day}>
            <div className="day">{day}</div>
            <div className="tiles">{ids.map((k, j) => <img key={j} src={ITEMS[k].src} alt="" loading="lazy" />)}</div>
          </section>
        ))}
      </div>
    </div>
  )
}

/* ---------- why Prismo: the Picasa gap ---------- */

function Story() {
  return (
    <section className="section container" id="why" aria-labelledby="why-h">
      {/* three columns: the old way on the left, the story in the middle, the Prismo way on the right */}
      <div className="story">
        <figure className="story-fig rise" style={{ '--i': 1 }}>
          <figcaption>Today: folder by folder<span>Open, look, go back, repeat.</span></figcaption>
          <LiveExplorer />
        </figure>
        <div className="story-text">
          <Head id="why-h" title="Picasa did this. Then it was gone." />
          <div className="body">
            <p className="rise">Years ago, Google Picasa gave Windows a gallery that felt like a phone: every photo on your PC, by date, one scroll away. Google retired it in 2016.</p>
            <p className="muted rise" style={{ '--i': 1 }}>Since then, File Explorer makes you open folder after folder, go back, and try the next one.</p>
            <p className="rise" style={{ '--i': 2 }}><strong>Prismo sets out to fill that gap:</strong> a phone-style gallery for your PC that is free, offline and fast.</p>
          </div>
        </div>
        <figure className="story-fig rise" style={{ '--i': 2 }}>
          <figcaption>With Prismo: one timeline<span>Every folder and drive, by date.</span></figcaption>
          <LiveTimeline />
        </figure>
      </div>
    </section>
  )
}

/* ---------- inside the app: cards in a row, the centred one is lit ---------- */

const CARDS = [
  {
    head: 'Grid',
    name: 'Every photo by date, or by folder',
    text: 'One timeline by day, month or year, your real folders, or every file in one list.',
    body: (
      <>
        <div className="row2"><b>View</b><span>Timeline · Folders · All files</span></div>
        {[['Saturday, 3 May', '8'], ['Sunday, 27 April', '3'], ['March 2025', '41']].map(([n, c]) => (
          <div className="row2" key={n}><span>{n}</span><span>{c}</span></div>
        ))}
        <p style={{ color: 'var(--muted)' }}>Date headers: smart, day, week, month or year</p>
      </>
    ),
  },
  {
    head: 'Search',
    name: 'Search and filters',
    text: 'Find files by name, folder, camera or date, then narrow to photos, videos, screenshots or a file type.',
    body: (
      <>
        <div className="row2"><b>“x-t5 coast”</b><span>3 results</span></div>
        {[['IMG_2413.JPG', 'Holidays'], ['IMG_2426.JPG', 'Holidays'], ['IMG_2439.MP4', 'Camera']].map(([n, f]) => (
          <div className="row2" key={n}><span>{n}</span><span>{f}</span></div>
        ))}
        <p style={{ color: 'var(--muted)' }}>Photos · Videos · Portrait · Screenshots · JPG · HEIC</p>
      </>
    ),
  },
  {
    head: 'Viewer',
    name: 'A viewer made for browsing',
    text: 'Zoom, rotate, full screen, a filmstrip through the folder, and a slideshow.',
    body: (
      <>
        <div className="row2"><span>Zoom in · out · fit</span><span>+ · − · 0</span></div>
        <div className="row2"><span>Rotate</span><span>R</span></div>
        <div className="row2"><span>Full screen</span><span>F</span></div>
        <div className="row2"><span>Slideshow</span><span>Space</span></div>
        <div className="row2"><span>Filmstrip</span><span>12 of 42</span></div>
      </>
    ),
  },
  {
    head: 'Video',
    name: 'Videos play right inside',
    text: 'MP4, MOV, MKV, WebM, AVI, camcorder MTS, WMV and more, with subtitles, sound tracks, a preview on the seek bar, and resume.',
    body: (
      <>
        <div className="meter"><i /></div>
        <div className="row2"><span>0:14 / 0:38</span><span>Speed 1×</span></div>
        <div className="row2"><span>Sound</span><span>English (2 tracks)</span></div>
        <div className="row2"><span>Subtitles</span><span>clip.srt</span></div>
      </>
    ),
  },
  {
    head: 'Audio',
    name: 'Music and voice notes too',
    text: 'MP3, FLAC, M4A, WAV and more, under their own filter so they never crowd your photos. See the album art, or watch the sound move.',
    body: (
      <>
        <div className="row2"><span>Show</span><span>Album art, Waveform, Bars, Circle</span></div>
        <div className="row2"><span>Filters</span><span>Audio, or with photos and videos</span></div>
        <div className="row2"><span>No album art</span><span>a music note</span></div>
      </>
    ),
  },
  {
    head: 'People and Categories',
    name: 'Tag people and categories',
    text: 'Right-click photos or a whole folder and tick who is in them or what they show. No face scanning.',
    body: (
      <>
        <div className="row2"><b>Who is in “Goa trip”?</b><span>12 items</span></div>
        {[['✓ Asha', 'all 12'], ['– Ravi', 'in 2 of 12'], ['✓ Beach', 'category']].map(([n, c]) => (
          <div className="row2" key={n}><span>{n}</span><span>{c}</span></div>
        ))}
        <p style={{ color: 'var(--muted)' }}>Filter: all of them, any of them, or untagged</p>
      </>
    ),
  },
  {
    head: 'Collections',
    name: 'Favorites, Hidden and Trash',
    text: 'Favorite or hide anything with one shortcut. Deleted files wait in Trash until you remove them for good.',
    body: (
      <>
        <div className="row2"><span>Favorites</span><span>Ctrl+D</span></div>
        <div className="row2"><span>Hidden</span><span>Ctrl+H</span></div>
        <div className="row2"><span>Trash</span><span>Delete</span></div>
        <div className="row2"><span>Recently added</span><span>newest first</span></div>
      </>
    ),
  },
  {
    head: 'Duplicates',
    name: 'Find and clear out duplicates',
    text: 'Finds exact copies, even under another name or in another folder. Select every extra copy in one click, keep the one you want, and send the rest to Trash.',
    body: (
      <>
        <div className="row2"><b>IMG_2413.JPG · 4.2 MB each</b><span>3 copies</span></div>
        <div className="row2"><span>Select extra copies</span><span>keeps one of each</span></div>
        <div className="row2"><span>Keep this copy</span><span>right-click</span></div>
        <div className="row2"><span>Show</span><span>by date, by file, or both</span></div>
        <p style={{ color: 'var(--muted)' }}>Prismo never removes a copy on its own</p>
      </>
    ),
  },
  {
    head: 'Select many',
    name: 'Select many at once',
    text: 'Drag a box from empty space, as in File Explorer, or tick a whole day with one click. Then trash, move, copy or favorite them all.',
    body: (
      <>
        <div className="row2"><span>Drag a box</span><span>from empty space</span></div>
        <div className="row2"><span>A whole day or folder</span><span>its check circle</span></div>
        <div className="row2"><span>A range</span><span>Shift + click</span></div>
        <div className="row2"><span>Everything</span><span>Ctrl+A</span></div>
      </>
    ),
  },
  {
    head: 'Location',
    name: 'Where it was taken',
    text: 'Info shows the town or city a photo or video was taken in, worked out offline from its GPS, with a link to open it on a map.',
    body: (
      <>
        <div className="row2"><b>Location</b><span>Panaji, Goa, India</span></div>
        <div className="row2"><span>GPS</span><span>15.4909, 73.8278</span></div>
        <div className="row2"><span>Open in map</span><span>only when you click</span></div>
        <div className="row2"><span>Motion photos</span><span>play their clip</span></div>
      </>
    ),
  },
  {
    head: 'Rename and Move',
    name: 'Rename and move files',
    text: 'Rename with F2, or move files to another folder. Prismo follows the file, favorite and all.',
    body: (
      <>
        <div className="row2"><span>Rename</span><span>F2</span></div>
        <div className="row2"><span>Move to folder…</span><span>one or a selection</span></div>
        <div className="row2"><span>Name already taken</span><span>keep both · skip</span></div>
      </>
    ),
  },
  {
    head: 'HEIC, AVIF and RAW',
    name: 'iPhone and camera RAW photos',
    text: 'HEIC, AVIF and RAW files (CR2, NEF, ARW, DNG…) get thumbnails and open in the viewer. Nothing extra to install.',
    body: (
      <>
        <div className="row2"><span>IMG_0412.HEIC</span><span>iPhone</span></div>
        <div className="row2"><span>DSC_2210.NEF</span><span>Nikon</span></div>
        <div className="row2"><span>_MG_8841.CR2</span><span>Canon</span></div>
        <div className="row2"><span>DSC01957.ARW</span><span>Sony</span></div>
      </>
    ),
  },
  {
    head: 'Open with',
    name: 'Your default photo viewer',
    text: 'Open a photo or video from File Explorer and it shows at once, with its folder on the filmstrip.',
    body: (
      <>
        <div className="row2"><span>Opened from</span><span>File Explorer</span></div>
        <div className="row2"><span>Filmstrip</span><span>this folder, 1 of 42</span></div>
        <div className="row2"><span>Waits for the library</span><span>no</span></div>
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
        <Head id="feat-h" title="Everything a gallery should do." intro="What Prismo does, one card each. Use the arrows, swipe, or click the card behind." center />
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
          {CARDS.map((c, i) => (
            <div className="capi" data-on={i === at} aria-hidden={i !== at} key={c.head}>
              <h3>{c.name}</h3>
              <p>{c.text}</p>
            </div>
          ))}
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
  { k: 'open', name: 'Source available', text: 'Read the code, build it yourself, change it, share it for free. Free at home and at work; not for sale.', row: ['Source code', 'public', true] },
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
  { h: 'Point it at your folders.', p: 'A folder, a USB drive or a network share. Nothing is imported or copied.' },
  { h: 'It reads them once.', p: 'Dates and camera details are read in the background, and thumbnails are kept. Browse while it works.' },
  { h: 'Browse by date.', p: 'Every photo and video on one timeline, the way your phone shows them.' },
  { h: 'It keeps up by itself.', p: 'Files you add, rename or delete show up on their own. An unplugged drive goes grey and comes back with it.' },
]

function PinWindow({ on }) {
  return (
    <div className="window" data-ui>
      <div className="titlebar"><img src="/brand/prismo-mark.webp" alt="" /><b>{['Library folders', 'Indexing', 'Grid', 'Grid'][on]}</b></div>
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
          <div className="tlwin">
            {[['Saturday, 3 May', [0, 1, 2, 3, 4, 5, 6, 7]], ['Sunday, 27 April', [8, 9, 10]]].map(([day, ids]) => (
              <div key={day}>
                <b>{day}</b>
                <div className="grid">{ids.map((k) => <div className="tile" key={k}><img src={ITEMS[k].src} alt="" loading="lazy" /></div>)}</div>
              </div>
            ))}
          </div>
        )}
        {on === 3 && (
          <div className="prog">
            {[['IMG_2450.JPG', 'added · just now'], ['Trip 2 → Goa 2025', 'renamed'], ['IMG_2391.JPG', 'deleted'], ['NAS · Family', 'offline']].map(([f, st], i) => (
              <div className="line" key={f} style={{ opacity: i === 3 ? 0.6 : 1 }}><span>{f}</span><span>{st}</span></div>
            ))}
            <div className="thumbs">{ITEMS.slice(0, 6).map((it, k) => <img key={it.id} src={it.src} alt="" style={{ '--k': k }} className={k > 3 ? 'off' : undefined} />)}</div>
            <p className="note">The greyed tiles are on the drive that is off. They come back with it.</p>
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

// Each answer is a list of short parts: a plain sentence, or [label, sentence], or { cmd } for a command line.
const LINUX_FILE = DOWNLOADS.linux.split('/').pop()
const FAQ = [
  ['Can it delete or change my photos?', [
    'Not on its own. Prismo only reads your files to show them.',
    ['You decide:', 'Rename, Move to folder and Delete are there, but only run when you choose them.'],
    ['Delete is two steps:', 'Delete puts a file in Prismo’s Trash, where it is still on disk. Only “Delete permanently” removes it, after asking, and on Windows it goes to the Recycle Bin.'],
  ]],
  ['Does it need an account, a server or the internet?', [
    'No. It’s a normal desktop app: install it and open it.',
    ['No account, no server:', 'nothing to sign up for, and no Docker or web server to set up.'],
    ['Offline:', 'it works without internet and sends nothing anywhere.'],
  ]],
  ['Does it work with a USB drive, an external disk or a NAS?', [
    'Yes. Add the folder like any other.',
    ['When it’s unplugged:', 'its photos stay in the timeline, greyed out, with a note that the drive is offline. Nothing is lost, and they come back when you plug it in.'],
    ['Network folders:', 'they work the same way. The first scan of a big network library takes longer than a local disk.'],
  ]],
  ['Is it a Picasa replacement?', [
    'For browsing, yes.',
    ['Like Picasa:', 'every photo on your PC in one place, by date, fast, on your own computer, with favorites, albums and tags you add yourself.'],
    ['Unlike Picasa:', 'it doesn’t edit photos and has no web albums. It is free, its code is public, and it is still maintained.'],
  ]],
  ['Can it find duplicate photos?', [
    'Yes, exact copies.',
    ['How:', 'it compares what is inside the files, so a copy is found even under another name or in another folder.'],
    ['Clearing them out:', 'Select extra copies picks every copy but one of each, then Move to Trash removes them in one go. You choose which copy stays, and Prismo never removes one on its own.'],
    ['A day at a time:', 'click a date’s circle once to select its extra copies, twice for every copy. Or drag a box over them.'],
    ['Safe by default:', 'if every copy of a file is selected, Prismo asks first and offers to keep one of each. A note at the bottom shows how far the search for copies is.'],
    ['Not found:', 'a resized or edited version of a photo, since its content is different.'],
  ]],
  ['Does it recognise faces?', [
    'No, on purpose.',
    ['You tag people yourself:', 'right-click a photo, a selection or a folder, choose People…, and tick who is in them.'],
    'Prismo never looks at the pictures. The tags stay in Prismo on your PC, and your files are not changed.',
  ]],
  ['Windows says “Windows protected your PC”. Is it safe?', [
    'The installer isn’t code-signed yet, so Windows warns about it, as it does for any new download from an unknown publisher.',
    ['To install:', 'click More info, then Run anyway. It needs no admin rights.'],
    ['To check it:', 'the code is public, and every release on GitHub lists the SHA-256 checksums of its files.'],
  ]],
  ['Will it cope with a big library?', [
    'Yes.',
    ['The first time:', 'it reads every file once and makes thumbnails in the background. On a big library or a slow disk this takes a while, but you can browse meanwhile.'],
    ['After that:', 'thumbnails are kept and only what is on screen is loaded, so it opens and scrolls quickly.'],
  ]],
  ['How do I update or uninstall it?', [
    ['Update:', 'run the newer installer over the old one. Your library, people, favorites and settings stay.'],
    ['Uninstall:', 'Settings › Apps › Installed apps › Prismo.'],
    ['Your data:', 'the library data stays in %LOCALAPPDATA%\\Prismo until you delete that folder. Your photos and videos are never touched.'],
  ]],
  ['Which files does it open?', [
    ['Photos:', 'JPEG, PNG, WebP, GIF, HEIC and AVIF.'],
    ['RAW:', 'CR2, CR3, NEF, ARW, DNG and more, through the preview stored inside them.'],
    ['Videos:', 'MP4, MOV, MKV, WebM, AVI, 3GP, MTS / M2TS, TS, WMV, FLV, MPG and OGV.'],
    ['Audio:', 'MP3, M4A, FLAC, WAV, AIFF, OGG, Opus, WMA, AAC and more.'],
  ]],
  ['Does it play music?', [
    'Yes, and voice notes and audiobooks too.',
    ['Kept apart:', 'audio has its own filter, so a music folder never buries your photos. Pick Audio, or “Photos, videos and audio” to see everything together.'],
    ['While it plays:', 'the album art with the title and artist, or a waveform, bars or a ring of bars that move with the sound. A song without album art shows a music note.'],
  ]],
  ['Does it run on Linux or Mac?', [
    ['Linux:', 'yes, as an early preview, so expect some rough edges. It comes as a .deb package for Ubuntu, Debian and their relatives.'],
    'Install it with this command, so the libraries it needs come along:',
    { cmd: `sudo apt install ./${LINUX_FILE}` },
    ['Mac:', 'not yet.'],
  ]],
]

// A command in a box with a one-click Copy button.
function CopyCmd({ cmd }) {
  const [done, setDone] = useState(false)
  const copy = async () => {
    try { await navigator.clipboard.writeText(cmd) } catch {
      // older browsers or no clipboard permission: copy the selected text instead
      const t = document.createElement('textarea'); t.value = cmd; document.body.appendChild(t); t.select()
      document.execCommand('copy'); t.remove()
    }
    setDone(true); setTimeout(() => setDone(false), 1600)
  }
  return (
    <div className="cmd">
      <code>{cmd}</code>
      <button type="button" onClick={copy} aria-label={done ? 'Copied' : 'Copy the command'}>{done ? <Check /> : <Copy />}<span>{done ? 'Copied' : 'Copy'}</span></button>
    </div>
  )
}

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
                <div className="a" id={`faq-${i}`} role="region">
                  <div>
                    {a.map((part, j) =>
                      part.cmd ? <CopyCmd cmd={part.cmd} key={j} />
                        : Array.isArray(part) ? <p key={j}><b>{part[0]}</b> {part[1]}</p>
                          : <p key={j}>{part}</p>
                    )}
                  </div>
                </div>
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
        <img className="mark rise" src="/brand/prismo-mark.webp" alt="The Prismo logo" width="128" height="128" />
        <h2 id="dl-h" className="title rise" style={{ '--i': 1 }}>Get Prismo for your PC.</h2>
        <p className="intro rise" style={{ '--i': 2 }}>Free to use, for Windows 10 / 11 and Linux (Ubuntu, Debian). Version 2.0.0. Everything it needs is inside the installer.</p>
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
