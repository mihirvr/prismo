// Build-time only (scripts/prerender.mjs): the page as plain HTML, so search engines and link previews that don't
// run JavaScript still see every heading, feature, answer and download link. The browser then draws the live page
// over it (main.jsx).
import { renderToString } from 'react-dom/server'
import App from './App.jsx'
export { DOWNLOADS } from './Sections.jsx'

export function render() {
  return renderToString(<App />)
}
