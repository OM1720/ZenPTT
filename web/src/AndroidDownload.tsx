// Resolves the published Android version and offers its APK download link.
import { useEffect, useState } from 'react'
import { Icon } from './Icons'

export function AndroidDownload() {
  const [release, setRelease] = useState<{ version: string; code: number } | null>(null)
  const [loading, setLoading] = useState(true)
  useEffect(() => {
    const controller = new AbortController()
    const timeout = setTimeout(() => controller.abort(), 5000)
    let active = true
    void fetch('/app/latest', { cache: 'no-store', signal: controller.signal }).then(async response => {
      if (!response.ok) throw new Error('Release unavailable')
      const value: unknown = await response.json()
      if (!value || typeof value !== 'object' || !('version_name' in value) || !('version_code' in value)
        || typeof value.version_name !== 'string' || !/^[0-9A-Za-z._-]{1,40}$/.test(value.version_name)
        || typeof value.version_code !== 'number' || !Number.isSafeInteger(value.version_code) || value.version_code < 1) return
      if (active) setRelease({ version: value.version_name, code: value.version_code })
    }).catch(() => { /* Download remains available if metadata cannot be loaded. */ }).finally(() => {
      clearTimeout(timeout)
      if (active) setLoading(false)
    })
    return () => { active = false; clearTimeout(timeout); controller.abort() }
  }, [])
  const title = `Android app · ${release?.version ?? (loading ? 'loading version…' : 'version unavailable')}`
  return <a className="icon-button" href={release ? `/app/releases/${release.code}/download` : '/app/download'}
    download title={title} aria-label={title}><Icon name="download" /></a>
}
