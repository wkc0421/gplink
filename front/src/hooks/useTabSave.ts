import { useRouter } from 'vue-router'

interface SuccessOptions {
  onSuccess: (value: any) => void
}

interface BackOptions {
  onBefore?: () => boolean
}

// Global registry: normalised child routePath -> parent callback
const callbackRegistry = new Map<string, (value: any) => void>()

function normalisePath(p: string) {
  return '/' + p.replace(/^\/+/, '').replace(/\/+$/, '')
}

/**
 * Used in a parent view to open a child route and receive a result when the
 * child calls onBack(value).
 *
 * @param routePath  - the path segment of the child route, e.g. 'link/AccessConfig/Detail'
 * @param options    - { onSuccess(value) } called when the child navigates back with data
 */
export function useTabSaveSuccess(routePath: string, options: SuccessOptions) {
  const router = useRouter()
  const normPath = normalisePath(routePath)

  const onOpen = (params?: Record<string, any>) => {
    callbackRegistry.set(normPath, options.onSuccess)
    router.push({ path: normPath, query: params as any })
  }

  return { onOpen }
}

/**
 * Used in a child view to signal a successful save and navigate back.
 * Returns a Promise<boolean> – true if a parent callback was found and called.
 */
export function useTabSaveSuccessBack() {
  const router = useRouter()

  const onBack = async (value?: any, backOptions?: BackOptions): Promise<boolean> => {
    if (backOptions?.onBefore && !backOptions.onBefore()) {
      return false
    }

    const currentPath = normalisePath(router.currentRoute.value.path)

    // Find matching registered callback (exact match or suffix match)
    let foundKey: string | undefined
    for (const key of callbackRegistry.keys()) {
      if (currentPath === key || currentPath.endsWith(key) || currentPath.includes(key.replace(/^\//, ''))) {
        foundKey = key
        break
      }
    }

    if (foundKey) {
      const cb = callbackRegistry.get(foundKey)!
      callbackRegistry.delete(foundKey)
      try {
        cb(value)
      } catch {
        // ignore callback errors
      }
      router.back()
      return true
    }

    router.back()
    return false
  }

  return { onBack }
}
