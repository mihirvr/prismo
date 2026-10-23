import { useEffect, useState } from 'react'

const KEY = 'prismo-theme'

function systemTheme() {
  return matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

function saved() {
  try {
    const t = localStorage.getItem(KEY)
    return t === 'light' || t === 'dark' ? t : null
  } catch {
    return null
  }
}

// Follows the system until the visitor presses the toggle; after that their choice is kept.
export function useTheme() {
  const [theme, setTheme] = useState(() => document.documentElement.dataset.theme || systemTheme())

  useEffect(() => {
    document.documentElement.dataset.theme = theme
  }, [theme])

  useEffect(() => {
    const mq = matchMedia('(prefers-color-scheme: dark)')
    const onChange = () => { if (!saved()) setTheme(systemTheme()) }
    mq.addEventListener('change', onChange)
    return () => mq.removeEventListener('change', onChange)
  }, [])

  const toggle = () => {
    const next = theme === 'dark' ? 'light' : 'dark'
    try { localStorage.setItem(KEY, next) } catch { /* private window: the choice just lasts this visit */ }
    setTheme(next)
  }
  return [theme, toggle]
}
