// Renders the Halo icon, ring, and transmit fill from client state.
import { haloArcs, haloSpec } from './haloSpec'
import { Icon } from './Icons'
import type { ClientState } from './ZenPttClient'
import { useEffect, useRef, useState } from 'react'

export function Halo({ state }: { state: ClientState }) {
  const spec = haloSpec(state)
  const element = useRef<HTMLSpanElement>(null)
  const [size, setSize] = useState(380)
  useEffect(() => {
    const observer = new ResizeObserver(entries => { const width = entries[0]?.contentRect.width; if (width) setSize(width) })
    observer.observe(element.current!)
    return () => observer.disconnect()
  }, [])
  const center = size / 2, radius = center - 4
  return <span ref={element} className="halo" data-mode={spec.mode} data-quality={state.quality} data-icon={spec.icon}>
    <svg className="halo-art" viewBox={`0 0 ${size} ${size}`} aria-hidden="true">
      {spec.filled && <circle cx={center} cy={center} r={Math.max(8, radius - 24)} fill="currentColor" />}
      {spec.ring && <g className={spec.rotating ? 'halo-ring rotating' : 'halo-ring'} fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
        {spec.gap === 0 ? <circle cx={center} cy={center} r={radius} /> : haloArcs(radius, spec.gap).map((path, i) => <path key={i} d={path} />)}
      </g>}
    </svg>
    <span className={`halo-icon ${spec.filled ? 'inverse' : ''}`}><Icon name={spec.icon} /></span>
  </span>
}
