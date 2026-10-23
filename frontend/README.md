# Prismo website

Live at https://prismo.mihirr.in (Vercel).

React + Vite. Separate from the app: it has its own `package.json` and is not part of the Gradle build.

```
cd frontend
npm install
npm run dev       # local preview, usually http://localhost:5173
npm run build     # makes dist/
```

Hosting on Vercel: the project's Root Directory is `frontend`. Vercel finds Vite by itself (build `npm run build`, output `dist`). Each push to `main` deploys again.

- The page follows the system light / dark setting; the toggle in the top bar overrides it and is remembered in the browser.
- Background: one very slow gradient (`@shadergradient/react`) fixed behind the whole page, blurred, with a tinted veil and fine grain on top (the "frosted" look). Sections are frosted glass panels over it. Phones, reduced motion, data saver and no-WebGL visitors get the still picture `src/fallback.svg` instead.
- Fonts (Google Fonts): Bricolage Grotesque for headings, Instrument Sans for paragraphs, Geist inside the app mock-ups.
- Icons: a few hand-written SVGs in `src/icons.jsx`, plus `lucide-react` (ISC licence) for the click hint on the demo window.
- The demo window uses public-domain / CC0 sample photos in `public/photos/` (credits below). Camera names, dates and sizes in the demo are made up.
- The Download button stays off until the first release. When it exists, change the button in `src/Sections.jsx` (`Download`) and the nav button in `src/App.jsx`.

## Sample photo credits

The demo uses these public-domain / CC0 photos (no credit is legally needed; listed anyway). Full records, with source pages and licence links, are in `public/photos/credits.json`.

- "Rockaway Beach Sunset" by Romain Guy, CC0, via Wikimedia Commons (https://commons.wikimedia.org/w/index.php?curid=92811251)
- "Forest path, Ånnaboda" by DimiTalen, CC0, via Wikimedia Commons (https://commons.wikimedia.org/w/index.php?curid=125157086)
- "Flower Garden Kromeriz" by P.matel, public domain, via Wikimedia Commons (https://commons.wikimedia.org/wiki/File:Flower_Garden_Kromeriz.jpg)
- "Whipple Dam Lake reflective" by Smallbones, CC0, via Wikimedia Commons (https://commons.wikimedia.org/w/index.php?curid=51122411)
- "Harbour chapel Nea Artaki Euboea Greece" by Jebulon, CC0, via Wikimedia Commons (https://commons.wikimedia.org/w/index.php?curid=51510430)
- "Nastapoka River Waterfall" by NicolasPerrault, CC0, via Wikimedia Commons (https://commons.wikimedia.org/w/index.php?curid=19240446)
- "Old church in Amsterdam city, the Amstelkerk church at the old town square Amstelveld" by Fons Heijnsbroek, CC0, via Wikimedia Commons (https://commons.wikimedia.org/w/index.php?curid=117017947)
- "Sogi Park Autumn Leaves" by aimnotboy, CC0, via Wikimedia Commons (https://commons.wikimedia.org/w/index.php?curid=86896811)
- "Red fox, wild animal background", CC0, via rawpixel (https://www.rawpixel.com/image/6024398/photo-image-background-public-domain-nature)
- "Free farmers market, vegetables display", CC0, via rawpixel (https://www.rawpixel.com/image/5913183/photo-image-public-domain-wooden-free)
- "Beagle dog corded bicycle street", CC0, via rawpixel (https://www.rawpixel.com/image/3337239/free-photo-image-alloy-wheel-animal-animals-images-pictures)
