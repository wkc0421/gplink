import fs from 'fs'
import * as path from 'path'

const rootPath = path.resolve(__dirname, '../../')
const modulesBasePath = 'src/modules'

function registerModulesAlias() {
  const modulesAlias = {}
  const pattern = path.resolve(rootPath, modulesBasePath)
  const folders = fs.readdirSync(pattern)

  for (const name of folders || []) {
    const modulePath = path.resolve(modulesBasePath, name)
    const configPath = path.resolve(rootPath, modulesBasePath, `${name}/config.json`)

    modulesAlias[`@${name}`] = modulePath

    if (!fs.existsSync(configPath)) continue

    try {
      const content = JSON.parse(fs.readFileSync(configPath, 'utf-8'))
      if (content.aliasName) {
        modulesAlias[content.aliasName] = modulePath
      }
    } catch (err) {
      console.warn(`读取或解析 ${configPath} 时出错:`, err)
    }
  }
  return modulesAlias
}

export { registerModulesAlias }
