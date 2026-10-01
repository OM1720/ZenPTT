// Mouse and keyboard contribute to one physical hold. Canceling the page releases all.
export class PttHold {
  private readonly sources = new Set<string>()
  constructor(private readonly down: () => boolean, private readonly up: () => void) {}
  press(source: string) {
    if (this.sources.has(source)) return
    this.sources.add(source)
    if (this.sources.size === 1) this.down()
  }
  release(source: string) {
    if (this.sources.delete(source) && this.sources.size === 0) this.up()
  }
  cancel() { this.sources.clear(); this.up() }
}

export function isTyping(target: EventTarget | null): boolean {
  return target instanceof Element && target.closest('input, textarea, select, [contenteditable]:not([contenteditable="false"])') !== null
}
