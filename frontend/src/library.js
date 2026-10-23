import { PHOTOS } from './photos.js'

// Sample library for the live demo: the pictures are public-domain / CC0 stock photos,
// the folders, dates, durations and camera data are made up for the demo.
const FOLDERS = ['Camera', 'Holidays', 'NAS · Family']
const DAYS = ['Saturday, 3 May', 'Sunday, 27 April', 'Friday, 11 April']
const CAMERAS = ['Pixel 8', 'Galaxy S24', 'X-T5', 'EOS R6']

export const ITEMS = PHOTOS.map((p, i) => {
  const video = i % 5 === 3
  return {
    id: i,
    src: p.file,
    title: p.title,
    folder: FOLDERS[i % 3 === 0 ? 2 : i % 3 === 1 ? 0 : 1],
    day: DAYS[Math.min(Math.floor(i / 6), DAYS.length - 1)],
    video,
    dur: video ? `0:${String(14 + ((i * 7) % 40)).padStart(2, '0')}` : null,
    progress: video ? [0.35, 1, 0][i % 3] : 0,
    fav: i === 1 || i === 6,
    camera: CAMERAS[i % CAMERAS.length],
    name: `IMG_${String(2400 + i * 13).padStart(4, '0')}.${video ? 'MP4' : 'JPG'}`,
    size: `${(2.1 + ((i * 37) % 50) / 10).toFixed(1)} MB`,
  }
})

export { FOLDERS }
