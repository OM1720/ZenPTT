// Resamples microphone PCM to 16 kHz with a windowed-sinc anti-aliasing filter.
// State persists across render quanta, so block boundaries cannot change timing.
export class Resampler {
  private readonly history = new Float32Array(63)
  private readonly coefficients = new Float64Array(63)
  private position = 0
  private inputIndex = 0
  private nextOutput = 0
  private previous = 0
  private readonly step: number

  constructor(inputRate: number, outputRate: number) {
    if (inputRate < 8000 || inputRate > 192000 || outputRate < 8000 || outputRate > 192000) throw new Error('Unsupported sample rate')
    this.step = inputRate / outputRate
    const cutoff = 0.45 * Math.min(1, outputRate / inputRate)
    let sum = 0
    for (let i = 0; i < 63; i++) {
      const x = i - 31
      const sinc = x === 0 ? 2 * cutoff : Math.sin(2 * Math.PI * cutoff * x) / (Math.PI * x)
      this.coefficients[i] = sinc * (0.42 - 0.5 * Math.cos(2 * Math.PI * i / 62) + 0.08 * Math.cos(4 * Math.PI * i / 62))
      sum += this.coefficients[i]!
    }
    for (let i = 0; i < 63; i++) this.coefficients[i] = this.coefficients[i]! / sum
  }

  push(input: Float32Array): Float32Array {
    const output: number[] = []
    for (const sample of input) {
      this.history[this.position] = sample
      let filtered = 0
      for (let i = 0; i < 63; i++) filtered += this.history[(this.position - i + 63) % 63]! * this.coefficients[i]!
      this.position = (this.position + 1) % 63
      while (this.nextOutput <= this.inputIndex) {
        const fraction = this.inputIndex - this.nextOutput
        output.push(this.previous * fraction + filtered * (1 - fraction))
        this.nextOutput += this.step
      }
      this.previous = filtered
      this.inputIndex++
    }
    return Float32Array.from(output)
  }
}
