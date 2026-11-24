// Runs after both builds (package.json "build"): puts the server-rendered page into dist/index.html.
import { readFile, writeFile, rm } from 'node:fs/promises'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { dirname, join } from 'node:path'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const page = join(root, 'dist', 'index.html')
const server = join(root, 'dist-ssr', 'entry-server.js')

const { render, DOWNLOADS } = await import(pathToFileURL(server).href)
const html = render()
let shell = await readFile(page, 'utf8')

// App details for search engines (schema.org SoftwareApplication). The version and download links come from the
// download buttons' own list (Sections.jsx DOWNLOADS), so a release only changes them there.
const version = DOWNLOADS.windows?.match(/\/v(\d+\.\d+\.\d+)\//)?.[1]
const details = {
  '@context': 'https://schema.org',
  '@type': 'SoftwareApplication',
  name: 'Prismo',
  description: "A free, open-source photo and video gallery for Windows and Linux: every photo and video on your PC in one timeline, like your phone's gallery. Offline, no account.",
  url: 'https://prismo.mihirr.in/',
  image: 'https://prismo.mihirr.in/og.jpg',
  applicationCategory: 'MultimediaApplication',
  operatingSystem: 'Windows 10, Windows 11, Linux',
  ...(version && { softwareVersion: version }),
  downloadUrl: [DOWNLOADS.windows, DOWNLOADS.linux].filter(Boolean),
  license: 'https://opensource.org/licenses/MIT',
  isAccessibleForFree: true,
  offers: { '@type': 'Offer', price: '0', priceCurrency: 'USD' },
  sameAs: ['https://github.com/mihirvr/prismo'],
}
const marker = '<!--app-details-->'
if (!shell.includes(marker)) throw new Error('dist/index.html has no app-details marker')
shell = shell.replace(marker, `<script type="application/ld+json">${JSON.stringify(details).replace(/</g, '\\u003c')}</script>`)
const empty = '<div id="root"></div>'
if (!shell.includes(empty)) throw new Error('dist/index.html has no empty #root to fill')
await writeFile(page, shell.replace(empty, `<div id="root">${html}</div>`))

// The sitemap: one page. lastmod = the day of this build (a deploy is when the page changes).
const today = new Date().toISOString().slice(0, 10)
await writeFile(join(root, 'dist', 'sitemap.xml'), `<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
  <url><loc>https://prismo.mihirr.in/</loc><lastmod>${today}</lastmod></url>
</urlset>
`)
await rm(join(root, 'dist-ssr'), { recursive: true, force: true })
console.log(`prerendered ${html.length} characters into dist/index.html`)
