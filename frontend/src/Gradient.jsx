import { useEffect } from 'react'
import { ShaderGradientCanvas, ShaderGradient } from '@shadergradient/react'

// The logo's colours: blue, violet, pink, orange. Light and dark pages use two moods of the same light.
// The speed is kept very low on purpose: the movement should only be noticed when you look for it.
const MOODS = {
  light: { color1: '#4aa8ff', color2: '#ff6fae', color3: '#ffb84d', brightness: 1.2 },
  dark: { color1: '#2f4cff', color2: '#d13cf0', color3: '#ff7a38', brightness: 1.05 },
}

export default function Gradient({ theme, onReady }) {
  const m = MOODS[theme]
  useEffect(() => { const t = setTimeout(onReady, 500); return () => clearTimeout(t) }, [onReady])
  return (
    <ShaderGradientCanvas
      style={{ position: 'absolute', inset: 0 }}
      pixelDensity={0.6}
      pointerEvents="none"
      lazyLoad={false}
    >
      <ShaderGradient
        control="props"
        type="waterPlane"
        animate="on"
        uSpeed={0.035}
        uStrength={2.6}
        uDensity={1.1}
        uFrequency={5.5}
        uAmplitude={0}
        color1={m.color1}
        color2={m.color2}
        color3={m.color3}
        lightType="3d"
        brightness={m.brightness}
        reflection={0.1}
        grain="off"
        cAzimuthAngle={180}
        cPolarAngle={90}
        cDistance={3.4}
        cameraZoom={1}
        positionX={0}
        positionY={0}
        positionZ={0}
        rotationX={50}
        rotationY={0}
        rotationZ={-60}
        enableTransition={false}
      />
    </ShaderGradientCanvas>
  )
}
