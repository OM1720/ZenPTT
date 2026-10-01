// Test-only PCM oracle. Envelope correlation detects ordering; spectrum detects wrong audio.
export function signal(rate: number, seconds = 2, variant = 0): Float32Array {
  const levels = [0.2, 0.7, 0.35, 0.9, 0.15, 0.55, 0.8, 0.3]
  return Float32Array.from({ length: rate * seconds }, (_, i) => {
    const block = Math.floor(i / (rate / 4))
    const frequency = (block + variant) % 2 ? 1100 : 700
    return levels[block % levels.length]! * 0.4 * Math.sin(2 * Math.PI * frequency * i / rate)
  })
}
function correlation(a: number[], b: number[]) {
  const ma = a.reduce((s, x) => s + x, 0) / a.length
  const mb = b.reduce((s, x) => s + x, 0) / b.length
  let dot = 0, aa = 0, bb = 0
  for (let i = 0; i < a.length; i++) {
    const x = a[i]! - ma, y = b[i]! - mb
    dot += x * y; aa += x * x; bb += y * y
  }
  return aa && bb ? dot / Math.sqrt(aa * bb) : 0
}
function envelope(pcm: Float32Array, rate: number) {
  const size = rate / 50, result: number[] = []
  for (let start = 0; start + size <= pcm.length; start += size) {
    let sum = 0
    for (let i = start; i < start + size; i++) sum += Math.abs(pcm[i]!)
    result.push(sum / size)
  }
  return result
}
function spectrum(pcm: Float32Array, rate: number) {
  // Sample at 8 kHz: all fixture carriers lie well below its Nyquist frequency.
  const stride = rate / 8000, powers: number[] = []
  for (let band = 1; band <= 64; band++) {
    const coefficient = 2 * Math.cos(2 * Math.PI * band / 128)
    let power = 0
    for (let start = 0; start + 128 * stride <= pcm.length; start += rate / 50) {
      let previous = 0, older = 0
      for (let i = 0; i < 128; i++) {
        const current = pcm[start + i * stride]! + coefficient * previous - older
        older = previous; previous = current
      }
      power += Math.max(0, previous ** 2 + older ** 2 - coefficient * previous * older)
    }
    powers.push(power)
  }
  return powers
}
export function assertSignal(source: Float32Array, received: Float32Array, rate: number, toleranceMs = 0) {
  if (!source.length || !received.every(Number.isFinite)
    || Math.abs(source.length - received.length) > rate * toleranceMs / 1000) throw new Error('PCM duration mismatch')
  const a = envelope(source, rate), b = envelope(received, rate)
  let best = 0
  for (let lag = -5; lag <= 5; lag++) {
    const left = Math.max(0, -lag), right = Math.max(0, lag)
    const count = Math.min(a.length - left, b.length - right)
    if (count >= 20) best = Math.max(best, correlation(a.slice(left, left + count), b.slice(right, right + count)))
  }
  if (best <= 0.9) throw new Error(`PCM envelope mismatch: ${best}`)
  const x = spectrum(source, rate), y = spectrum(received, rate)
  const dot = x.reduce((s, value, i) => s + value * y[i]!, 0)
  const scale = Math.sqrt(x.reduce((s, v) => s + v * v, 0) * y.reduce((s, v) => s + v * v, 0))
  const similarity = scale ? dot / scale : 0
  if (similarity <= 0.9) throw new Error(`PCM spectrum mismatch: ${similarity}`)
}

export function trimSilence(samples: number[]): Float32Array {
  const first = samples.findIndex(value => Math.abs(value) > 0.002)
  let end = samples.length
  while (end > first && Math.abs(samples[end - 1]!) <= 0.002) end--
  return Float32Array.from(first < 0 ? [] : samples.slice(first, end))
}
