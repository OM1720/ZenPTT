import { mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs'

const lock = JSON.parse(readFileSync('package-lock.json', 'utf8'))
const notices = ['ZenPTT web runtime dependencies\nOpus and icon notices are provided in separate files in this directory.']
for (const [path, dependency] of Object.entries(lock.packages)) {
  if (!path || dependency.dev) continue
  const pkg = JSON.parse(readFileSync(`${path}/package.json`, 'utf8'))
  const license = readdirSync(path).find(name => /^(LICENSE|COPYING)(\.(txt|md))?$/i.test(name))
  if (!license) throw new Error(`License text missing for ${pkg.name}`)
  notices.push(`${pkg.name} ${pkg.version}\n${readFileSync(`${path}/${license}`, 'utf8').trim()}`)
}
mkdirSync('public/licenses', { recursive: true })
writeFileSync('public/licenses/THIRD-PARTY-NOTICES.txt', notices.join('\n\n---\n\n') + '\n')
