type ModuleResources = Record<string, any>

class ModuleRegistry {
  private modules = new Map<string, ModuleResources>()

  register(name: string, resources: ModuleResources) {
    this.modules.set(name, resources || {})
  }

  getResource(moduleName: string, resourceKey: string) {
    return this.modules.get(moduleName)?.[resourceKey]
  }

  getResourceItem(moduleName: string, resourceKey: string, itemKey: string) {
    return this.getResource(moduleName, resourceKey)?.[itemKey]
  }
}

export const moduleRegistry = new ModuleRegistry()
